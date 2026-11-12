# What the proof of concept does not cover

The proof of concept built the core loop (CLAUDE.md lists its scope) to check the design recommended in [architecture.md](architecture.md). This page lists what it deliberately leaves out and the gaps found by reviewing it, so nobody mistakes it for production behaviour or for the recommendation itself.

## Not built

- Front doors: no HTTP API and no file parser. `Ingestion.submit` is the call both would make. Acceptance is partial only; all-or-nothing is not implemented.
- Validation and screening are pass-through steps. Partner pause and in-flight limits (`partner.paused`, `max_in_flight`) are not read.
- Manual actions and resolution of `UNKNOWN` and `MISMATCH`. Both wait for a person. A late downstream event resolves `UNKNOWN`; nothing resolves `MISMATCH`, which ignores every later event. Reconciliation against statements is not built.
- Outbox dispatching. Every effect is written to `outbox` in the same transaction as the state change; nothing delivers them (partner webhooks, forwarding a cancellation, reconciliation, alerts).
- Scheduled singletons, partition creation ahead of time, grants for append-only tables, retention and erasure.
- Internal request expiry. A downstream can still report `EXPIRED`. A downstream that keeps reporting the status we already know keeps a payment waiting indefinitely.
- `LISTEN/NOTIFY`. Workers poll.

## Behaviour gaps

- **Too-late cancellation is silent.** A cancellation that arrives after acceptance (or when already requested, or on a `MISMATCH`) is recorded as a technical `STALE` event and marked applied. The partner is not told it was refused. Telling them needs a new partner status, and published statuses are forever.
- **One response window** serves `SENDING`, `PRESENTED` and `ACCEPTED`, though the last two wait on a debtor. A silent downstream moves them to `UNKNOWN` after the window and the retries.
- **Partner status order during a cancellation depends on delivery order.** A `PRESENTED` acknowledgement is kept off the partner's list only when it arrives on its own. If the downstream skips or reorders it, the partner sees `PRESENTED` after `CANCELLATION_REQUESTED`. Final state and money are the same.
- **Contradicting final events are `TECHNICAL`.** `PAID` after `CANCELLED` is recorded as a stale technical event with an `ALERT` outbox row. A business-audit query on `category = 'BUSINESS'` does not show it.
- **Provider events lose their source.** `occurred_at` and `recorded_at` are both the time the worker applied the step, `provider_timestamp` is our receive time, and the inbox keeps only `{"status": ...}`, not the raw message.
- **A payment whose send timed out before the reference was learned** ends `UNKNOWN` if the status query stays unanswered. Events the downstream sends for it stay `PARKED`.
- **Resends go through the worker's own adapter,** not the one named in `payment_request.downstream`. Fine with one downstream.
- **A reference the downstream gives that is unusable** (empty, over 200 characters, control characters, or held by another payment) sends the payment to `UNKNOWN`. `ProviderInbox.receive` still throws for such a reference in an event instead of rejecting it cleanly.
- **A downstream reason code** is stored without control characters and cut to 64 characters.

## Robustness gaps

- **Unmappable rows poison the claim.** `Store.claim` maps the row it claimed inside the claim transaction. A row with a state this build does not know, or a currency `Money` rejects (the schema allows both), makes every claim throw before a lease is set, so the earliest due row blocks all work. Constrain `state` in the schema and map outside the claim before this matters.
- **Partner input is not fully validated.** A NUL character or a very long end-to-end id makes the insert fail and rolls back the whole submission, contradicting partial acceptance. `Money.parse` has no length limit, and a very long amount holds the submit transaction for seconds to minutes.
- **The content hash has no version.** Changing its format makes older payments look like conflicts on resend. There is no persisted data in the PoC.
- **The lease is renewed by writes, not a heartbeat.** A single step that outlasts the lease can be taken over mid-step; the fenced write then fails, which is the safe outcome.
- **A status-outage streak resets on any other event for the payment.** In `SENDING` every other event moves the payment on, so nothing can interleave; revisit it before retries are used in another state.
- **Inputs are claimed before timers.** They are consumed by the run that claims them, so only a continuous flood of new events could delay a timeout. Within the inputs, claims go by due time with nulls last, not by arrival.
- `payment_event.recorded_at` comes from the injected clock. The migration creates partitions for 2026-11 to 2027-01 only; later rows land in the default partition, which must stay empty.

## Performance

Measured by the final review, not fixed:

- **The claim query scans `payment_request` on every poll.** The `OR`/`EXISTS` form and the `ORDER BY` cannot use the partial due index, and the input check is evaluated twice per candidate. About 250 to 300 ms per claim at 50k rows on default Postgres 16, against about 0.5 ms for a rewrite that finds timer-due rows and rows with inputs separately. Rewrite it before measuring throughput.
- **`Db` opens a new connection for every transaction (no pool),** and one `runOnce` makes several, plus about 20 small transactions per payment before the send. Use a pooled `DataSource` and fewer round trips (the claim already knows whether an input exists).

## Test and code hygiene

- The fake downstream lives in the main sources so it can back a demo; move it to test sources if it never does. `Worker`'s `afterSendRecorded` constructor parameter is a test seam in production code: it can block the send path, so remove it or make it internal before real use.
- `FakeDownstream` does not record a scripted reject, so it is not idempotent for rejections.
- No deterministic test covers the live-lease predicate in `Store.claim`; only the concurrent test would catch it, and only when the race occurs. `HardeningTests` uses a short sleep to hold a transaction open.
- `.gitignore` ignores `/bin/` only; nested `bin/` directories of a future module would show as untracked.
