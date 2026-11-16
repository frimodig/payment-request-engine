# What the proof of concept does not cover

The proof of concept built the core loop (CLAUDE.md lists its scope) to check the design recommended in [architecture.md](architecture.md). This page lists what it deliberately leaves out and the gaps found by reviewing it, so nobody mistakes it for production behaviour or for the recommendation itself.

## Not built

- Front doors: no HTTP API and no file parser. `Ingestion.submit` is the call both would make, and it ingests a whole submission in one transaction: the staged, chunked file ingestion the recommendation describes is not built. Acceptance is partial only; all-or-nothing is not implemented.
- Validation and screening are pass-through steps. Partner pause and in-progress limits (`partner.paused`, `max_in_flight`) are not read.
- Manual actions and resolution of `UNKNOWN` and `MISMATCH`. Both wait for a person. A late downstream event resolves `UNKNOWN`; nothing resolves `MISMATCH`, which ignores every later event. `UNKNOWN` does not re-check the downstream on its own.
- Reconciliation against statements. `SETTLED` arrives through the provider inbox like any downstream event; in production reconciliation produces it. Events carry no amount, so a partial or repeated credit is not detected, and nothing alerts on a payment that stays `PAID`.
- A circuit breaker and warm-up (designed for later). During an outage each payment backs off on its own, and new payments are still sent into it, each failing once before it backs off. After the outage, retries resume at full rate rather than ramping up.
- Jitter on the backoff. The waits are exact, so payments that enter an outage together retry together.
- Downstream styles other than synchronous. There is one fake downstream, and it keeps every idempotency key forever and answers a repeated key with the original result. A downstream that answers a repeated key with a reject would make the PoC record `REJECTED`.
- Outbox dispatching. Every effect is written to `outbox` in the same transaction as the state change; nothing delivers them (partner webhooks, forwarding a cancellation, reconciliation, alerts). `WebhookTarget` decides which partner URLs may be called, but nothing stores a partner's URL or calls it, so connecting only to the checked addresses and refusing redirects is not exercised. It allows any port on a public address, so a partner could point the engine at another party's mail or database port; limiting ports (for example to 443 and 8443) or the egress proxy is left to delivery.
- Scheduled singletons, partition creation ahead of time, grants for append-only tables, retention and erasure.
- Internal request expiry. A downstream can still report `EXPIRED`. A downstream that keeps reporting the status we already know keeps a payment waiting indefinitely.
- `LISTEN/NOTIFY`. Workers poll.

## Designed after the plan reviews, not built

The second and third adversarial reviews of the plan ([review-findings.md](review-findings.md#second-adversarial-review-of-the-plan), [third review](review-findings.md#third-review-does-the-plan-make-sense)) changed designs the proof of concept already has. Where the code and the plan differ:

- **Integrity.** The schema has no integrity controls: the application role can update any column, and the event sequence is not unique.
- **States.** There is no `NOT_PAID`, `PAID_LATE`, `SCREENING_REVIEW` or `QUARANTINED` ([states table](architecture.md#states-and-partner-statuses)). `ACCEPTED` times out to `UNKNOWN`; the design asks the downstream at a business deadline, then moves it to `NOT_PAID` or `MISMATCH`. `PAID` has no settlement deadline. A `PAID` event after `CANCELLED` is stale with an alert; the design moves it to `PAID_LATE`. There is no running total of credits.
- **The outage horizon applies to every payment.** The design applies it only to payments that may need a resend; an acknowledged payment keeps backing off. Nothing freezes the horizon, and nothing checks it against the downstream's key retention.
- **No gates.** There is no per-downstream or per-partner pause, no global kill switch and no maintenance-window freeze ([operations.md](operations.md#gates)). `partner.paused` and `max_in_flight` are not read. The downstream is stored when `SENDING` is recorded, not at routing.
- **The downstream key** is the random payment id plus the attempt, so it would not survive a database restore; the design derives it from the partner's own ids. The attempt increments only on the first send, which matches the design's attempt rule, but there is no manual resend.
- **Cancellation.** Expiry does not forward a cancellation, and the context does not record who asked.
- **The claim** looks for provider events and cancellations only; the design also takes approved manual actions, statement matches and undelivered cancellation forwards. There is no failure count: a payment that fails on every claim retries every lease period.
- **A reused message id** adds instructions to the existing submission instead of being rejected as a conflict.
- **Partitioning.** The event table is partitioned by recorded time and has a default partition; the design partitions only `payment_event`, by the payment's creation month, with none.
- **The currency exponent** comes from the JDK (`Money.kt`) instead of being stored with each amount.

## Behaviour gaps

- **Too-late cancellation is silent.** A cancellation that arrives after acceptance (or when already requested, or on a `MISMATCH`) is recorded as a technical `STALE` event and marked applied. The partner is not told it was refused. Telling them needs a new partner status, and published statuses are forever.
- **One response window** serves `SENDING`, `PRESENTED` and `ACCEPTED`, though the last two wait on a debtor. A silent downstream moves them to `UNKNOWN` one window after it stops answering; an unreachable one, after the outage horizon.
- **Partner status order during a cancellation depends on delivery order.** A `PRESENTED` acknowledgement is kept off the partner's list only when it arrives on its own. If the downstream skips or reorders it, the partner sees `PRESENTED` after `CANCELLATION_REQUESTED`. Final state and money are the same.
- **Contradicting final events are `TECHNICAL`.** `PAID` after `CANCELLED` is recorded as a stale technical event with an `ALERT` outbox row. A business-audit query on `category = 'BUSINESS'` does not show it.
- **Provider events lose their source.** `occurred_at` and `recorded_at` are both the time the worker applied the step, `provider_timestamp` is our receive time, and the inbox keeps only `{"status": ...}`, not the raw message.
- **A payment whose send timed out before the reference was learned** ends `UNKNOWN` if the status query stays unanswered past the outage horizon. Events the downstream sends for it stay `PARKED`.
- **A downstream that knows a payment in `SENDING` but does not move it within one response window** sends it to `UNKNOWN`. In `PRESENTED` and `ACCEPTED` the same answer keeps the payment waiting.
- **The idempotency key is `paymentId:attempt`, 38 characters,** longer than the 35 characters ISO 20022 identifiers allow. The adapter for an ISO downstream must map it.
- **Resends go through the worker's own adapter,** not the one named in `payment_request.downstream`. Fine with one downstream.
- **A reference the downstream gives that is unusable** (empty, over 200 characters, control characters, or held by another payment) sends the payment to `UNKNOWN`. `ProviderInbox.receive` throws for an event whose reference holds a NUL character; other unusable references in events are stored and parked, or matched to the payment that holds them.
- **A downstream reason code** is stored without control characters and cut to 64 characters.

## Robustness gaps

- **Unmappable rows poison the claim.** `Store.claim` maps the row it claimed inside the claim transaction. A row with a state this build does not know, or a currency `Money` rejects (the schema allows both), makes every claim throw before a lease is set, so the earliest due row blocks all work. Constrain `state` in the schema and map outside the claim before this matters.
- **Partner input is not fully validated.** A NUL character or a very long end-to-end id makes the insert fail and rolls back the whole submission, contradicting partial acceptance. `Money.parse` has no length limit, and a very long amount holds the submit transaction for seconds to minutes.
- **The content hash has no version.** Changing its format makes older payments look like conflicts on resend. There is no persisted data in the PoC.
- **The lease is renewed by writes, not a heartbeat.** A single step that outlasts the lease can be taken over mid-step; the fenced write then fails, which is the safe outcome. But nothing bounds a step: downstream calls and JDBC statements have no timeout, and leases and due times come from each instance's clock, not the database's. A hung call or a skewed clock lets a second worker call the downstream while the first is still in its call; the key then decides whether that creates a second payment.
- **An outage streak ends on a status answer that finds the payment, or any other event that changes something.** A send endpoint that stays down while status queries answer "not found" is one outage, so the payment reaches `UNKNOWN` at the horizon. A cancellation applied during an outage restarts the horizon and the backoff; a repeated webhook does not, because webhooks can arrive while status queries fail.
- **A cancelled payment the downstream never received ends in `UNKNOWN`.** A payment waiting in `SENDING` because its send failed moves to `CANCELLATION_REQUESTED` when cancelled, and the downstream then cannot find it. It could be cancelled outright, but only once nothing can still be in flight for it.
- **No fairness between partners or channels.** Claims go by inputs first, then due time. A large file delays every step of a payment submitted through the API after it.
- **Inputs are claimed before timers.** They are consumed by the run that claims them, so only a continuous flood of new events could delay a timeout. Within the inputs, claims go by due time with nulls last, not by arrival.
- `payment_event.recorded_at` comes from the injected clock. The migration creates partitions for 2026-11 to 2027-01 only; later rows land in the default partition, which must stay empty.

## Performance

Measured by the final review, not fixed:

- **The claim query scans `payment_request` on every poll.** The `OR`/`EXISTS` form and the `ORDER BY` cannot use the partial due index, and the input check is evaluated twice per candidate. About 250 to 300 ms per claim at 50k rows on default Postgres 16, against about 0.5 ms for a rewrite that finds timer-due rows and rows with inputs separately. That was measured once by hand during the review; no script or data generator for it is in the repository, so treat the figures as indicative. Rewrite the query before measuring throughput.

  The rewrite picks the payment in two steps inside the same `UPDATE`: first one with an unapplied input, found by joining the two input tables to `payment_request`, and only if there is none, one that is timer-due, which can use the partial due index. Both selections use `FOR UPDATE ... SKIP LOCKED` and `LIMIT 1`:

  ```sql
  WHERE p.id = COALESCE(
      (SELECT q.id FROM payment_request q
        WHERE (q.lease_until IS NULL OR q.lease_until <= :now)
          AND q.id IN (SELECT payment_id FROM provider_inbox WHERE status = 'NEW'
                       UNION ALL
                       SELECT payment_id FROM cancellation_request WHERE applied_at IS NULL)
        ORDER BY q.next_action_at, q.created_at
        LIMIT 1 FOR UPDATE OF q SKIP LOCKED),
      (SELECT q.id FROM payment_request q
        WHERE (q.lease_until IS NULL OR q.lease_until <= :now)
          AND q.next_action_at <= :now
        ORDER BY q.next_action_at, q.created_at
        LIMIT 1 FOR UPDATE SKIP LOCKED))
  ```

  Measure it against the current query on realistic data before adopting it. Like the current query, it leaves out approved `manual_action` rows, which are inputs too.
- **`Db` opens a new connection for every transaction (no pool),** and one `runOnce` makes several, plus about 20 small transactions per payment before the send. Use a pooled `DataSource` and fewer round trips (the claim already knows whether an input exists).

## Test and code hygiene

- The fake downstream lives in the main sources so it can back a demo; move it to test sources if it never does. `Worker`'s `afterSendRecorded` constructor parameter is a test seam in production code: it can block the send path, so remove it or make it internal before real use.
- `FakeDownstream` does not record a scripted reject, so it is not idempotent for rejections.
- No deterministic test covers the live-lease predicate in `Store.claim`; only the concurrent test would catch it, and only when the race occurs. `HardeningTests` uses a short sleep to hold a transaction open.
- `.gitignore` ignores `/bin/` only; nested `bin/` directories of a future module would show as untracked.

## Open in the design

- **The restore runbook needs more detail** before it can be rehearsed: it should record the highest attempt the downstream knows, cover payments re-created by re-ingestion, choose the payments to jump from the delivery log rather than a margin, log each sequence before delivery rather than after, and say how workers are made to ask now. A decision is also open on whether re-ingested instructions keep the instruction id partners already hold.
- **For partners without statement access**, whether a reject after their final `PAID` is a `MISMATCH` or stale with an alert is not yet decided, and the partner's statement access is not yet in the state machine's context.
