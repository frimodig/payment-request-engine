# PoC limits

What the PoC deliberately leaves out, so nobody mistakes it for production behaviour. The scope is the core loop in [CLAUDE.md](../CLAUDE.md).

## Not built

- Front doors: no HTTP API and no file parser. `Ingestion.submit` is the call both would make. Acceptance is partial only; all-or-nothing is not implemented.
- Validation and screening are pass-through steps. Partner pause and in-flight limits (`partner.paused`, `max_in_flight`) are not read.
- Manual actions and `MISMATCH`/`UNKNOWN` resolution. Both wait for a person and nothing resolves them except a matching late downstream event. Reconciliation against statements is not built.
- Outbox dispatching. Every effect is written to `outbox` in the same transaction as the state change; nothing delivers them (partner webhooks, forwarding a cancellation downstream, reconciliation, alerts).
- Scheduled singletons, partition creation ahead of time, grants for append-only tables, retention and erasure.
- Internal request expiry. A downstream can still report `EXPIRED`.
- `LISTEN/NOTIFY`. Workers poll.

## Known weaknesses

- Provider events are matched by downstream reference only. A payment whose send timed out before the reference was learned ends `UNKNOWN`, and events the downstream sends for it stay `PARKED` until reconciliation exists.
- A worker's lease is renewed by each of its writes, not by a heartbeat. A single step that outlasts the lease can be taken over mid-step; the fenced write then fails, which is the safe outcome.
- The claim query checks inputs with `EXISTS` subqueries and orders unapplied inputs first, then by due time, evaluating the input `EXISTS` checks twice per candidate, so it cannot use the partial due index and has no supporting index for the inbox or cancellation lookups. Fine for a PoC, to be measured before anything else.
- A cancellation that arrives too late (accepted, paid, mismatch, already requested) is recorded as a technical `STALE` event and marked applied, but the partner is not told it was refused. Telling them needs a new partner status, and published statuses are forever.
- `claim` is one `UPDATE ... (SELECT ... LIMIT 1 FOR UPDATE SKIP LOCKED)`. When two workers race for the same candidate, the loser can get no row although other payments are due, so `runOnce` returns false once for nothing. Callers that treat false as idle lose one poll.
- `runOnce` reads pending inputs in separate transactions after the claim, though the claim already knows whether any exist. The `Instant` read-back and nullable-int writes are repeated by hand in `Store` and the test tables.
- The instruction content hash changed (length-prefixed fields): payments ingested before it would be classed as conflicts on resend. There is no persisted data in the PoC.
- Inputs are claimed before timers. They are consumed by the run that claims them, so only a continuous flood of new events could delay a timeout; nothing ages overdue timers.
- The status-outage streak resets on any other event for the payment. In `SENDING` every other event moves the payment on, so nothing can interleave; the streak would need rethinking if retries are ever used in another state.
- The content hash has no version. A format change makes older payments look like conflicts on resend.
- `.gitignore` ignores `/bin/` only; nested `bin/` directories of a future module would show as untracked.
- `Db` opens a new connection for every transaction (no pool), and one `runOnce` makes several. Use a pooled `DataSource` before measuring throughput.
- A status query that is unavailable is retried `statusRetries` times (default 3, 30 s apart), counted per outage (the events since the last event of another type), before the payment becomes `UNKNOWN`.
- A downstream reason code is stored without control characters and cut to 64 characters.
- The provider status in `provider_inbox.payload` is assumed to be one of the known values.
- `payment_event.recorded_at` comes from the injected clock. The migration creates partitions for 2026-11 to 2027-01 only; later rows land in the default partition, which the design says must stay empty.
- The fake downstream lives in the main sources so it can back a demo; move it to test sources if it never does. `Worker`'s `afterSendRecorded` constructor parameter is a test seam in production code: it can block the send path, so remove it or make it internal before real use.

## Decisions taken while building

See the commit messages. The state machine takes and returns a `Context` (`state`, last known downstream position, cancellation requested), which is why V2 adds two columns.
