# Recommendation: partner payment processing engine

![Architecture](architecture.png)

This is a recommendation for how to build the engine described in the brief. It was checked by building the core loop as a proof of concept; [what that showed](#evidence-from-the-proof-of-concept) supports the recommendation, and [what it does not cover](poc-limits.md) is listed. The data model is in [database.md](database.md).

## The brief

A financial institution needs an engine that receives payment instructions from external partners (pain.013-style messages). Phase 1 is file and batch processing, with potentially thousands of requests per file. Phase 2, the strategic direction, is near-real-time API submission, one request at a time. The engine receives, validates, transforms to an internal representation, applies business rules, executes or routes, tracks the lifecycle of every instruction, and produces acknowledgements and status. Downstream payment systems can be synchronous, asynchronous, file-based or message-based.

| The brief asks for | Where it is answered |
|---|---|
| Phase 1: files with thousands of requests | [Front doors](#one-core-two-front-doors), [Scale](#data-availability-and-scale) |
| Phase 2: one request at a time through an API | [Front doors](#one-core-two-front-doors) |
| Acknowledgement and processing status for the partner | [Acknowledgements and status](#acknowledgements-and-status) |
| Validates | [Validation and business rules](#validation-and-business-rules) |
| Transforms into an internal representation | [Internal representation](#internal-representation) |
| Applies business rules | [Validation and business rules](#validation-and-business-rules) |
| Executes or routes | [Downstream systems](#downstream-systems) |
| Tracks the lifecycle of every instruction | [Processing model](#processing-model), [State machine](#state-machine), [database.md](database.md) |
| Downstream systems of any style | [Downstream systems](#downstream-systems) |

## Recommendation in brief

- **One core, two front doors.** Every submission becomes individual instructions processed by one pipeline. Files and the API are two ingestion adapters into the same core, so Phase 2 adds a door rather than a second engine.
- **One modular service, N identical instances, one PostgreSQL.** Every state with automatic work has a due time. A worker claims what is due or has a new input, under a lease with fenced writes. No queue broker, no per-state services.
- **An explicit, pure state machine.** Out-of-order downstream events are resolved by the state graph, never by clocks. Unknown outcomes are a state with an owner; a timeout never becomes a failure.
- **Everything the engine does is an event, and every effect is an outbox row,** written in the same transaction as the state change. Nothing is lost if a worker dies, and every status can be explained.
- **Downstreams hide behind adapters.** One adapter per downstream, one internal vocabulary, our own idempotency key on every call.
- **Build the failure paths first.** Duplicates, reordering, timeouts and takeovers decide whether this engine is trustworthy; the happy path is the easy part.

## Questions first

The design changes less with the answers than the front doors do, but these decide scope.

1. **What drives the design?** Do partners want real time, or is the change internal? What happens today, and is any of it automated?
2. **Scope.** One-time payments only, or payment plans too? Bills only, or online checkout as well? Alongside existing e-invoicing and direct debit schemes, or an alternative to them?
3. **Files.** Do partners already export these files? Is there an existing host-to-host channel to reuse?
4. **Constraints.** Which currencies, downstream systems and SLAs are in scope?

pain.013 is a Creditor Payment Activation Request (Request to Pay). The status goes back as pain.014, and an accepted request is followed by a separate credit transfer, possibly several or a partial one. The final status waits on the debtor, so status is asynchronous in both phases: Phase 2 removes batching, not asynchrony.

## One core, two front doors

Every submission becomes individual instructions processed by one pipeline. Which front doors exist depends on question 1:

- Partners drive files: files are a permanent channel and the API is added beside it, never a forced migration.
- Partners want an API, or nobody produces files yet: go straight to the API, with a bulk endpoint.
- Rejected: a temporary file channel partners are later moved off. Partners see a forced migration as a downgrade, and anything given to partners is supported for years.

### File vs API

Granularity is not the difference: status, webhooks and cancellation are per instruction either way. The API adds feedback inside the partner's own call, lower latency, and fine-grained access (keys scoped to submit or cancel). Cancellation is only worth building through the API. Files fit existing ERP exports, are efficient in bulk, and the file itself is an audit record.

A file is a bulk API call with the same idempotency rules:

- Deduplicate per instruction (partner + end-to-end id). A corrected resend gets a new message id; already-accepted rows are no-ops.
- Same id with different content is a conflict, rejected explicitly. Never a silent edit.
- Control totals validate the file as received; the report gives accepted and rejected counts against them.
- Per-partner setting: partial acceptance (default) or all-or-nothing.

### Out of scope

Payment plans and mandates. They bring due-date rules, missed payments, under- and overpayment, and possibly consumer-credit regulation. That logic belongs to the biller or a separate product. The engine keeps an optional parent reference on each instruction and emits status events rich enough to reconcile a plan against.

## Acknowledgements and status

The partner needs an acknowledgement and processing status. These are different things at three levels:

1. **Receipt.** The API answers inside the call: accepted for processing, or a clear, non-retryable error. A file gets a file-level acknowledgement with accepted and rejected counts against its control totals, and a reason per rejected row.
2. **Per-instruction status.** Each partner-visible state change produces a status the partner can receive. Delivery channels, all fed from the same outbox: webhooks, a status API the partner can poll, and pain.014 files for file partners. Delivery is at least once with a stable event id per status, so partners deduplicate. A partner polling gets the current state, so a missed webhook does not matter.
3. **Final outcome.** For a Request to Pay the final status waits on the debtor and on the credit transfer that follows, so it arrives later and asynchronously, in both phases.

The status vocabulary is a contract, and published values are forever. Expose only states that mean something to the partner: `PRESENTED`, `ACCEPTED`, `CANCELLATION_REQUESTED`, `PAID`, `REJECTED`, `REFUSED`, `EXPIRED`, `CANCELLED` (`RECEIVED` is acknowledged at receipt). Keep validation, screening, sending, `UNKNOWN` and `MISMATCH` internal. "Accepted" must be documented as accepted for processing, not as paid.

## Internal representation

Parse and validate at the edge, then work on one canonical instruction. Channel formats (pain.013 XML in a file, JSON in the API) and downstream formats never reach the core.

- **Canonical instruction.** Partner and message references, end-to-end id, amount as integer minor units plus ISO 4217 currency, debtor and creditor identification, remittance reference, expiry, optional parent reference, and a content hash for conflict detection.
- **One mapping in, one per downstream out.** Ingestion adapters map the channel format to the canonical instruction; downstream adapters map it to the downstream's format and its replies back to the internal status vocabulary and reason codes.
- **Keep the original.** The received file stays in object storage and is the audit record for what the partner sent.
- **Version the model and the contracts.** Unsupported message versions get a clear non-retryable error.

## Validation and business rules

- **Synchronous at ingestion:** schema, required fields, duplicate ids, known partner, per-partner usage profile, amount parsed exactly (see [Money and time](#money-and-time)).
- **Asynchronous before routing:** sanctions, AML, fraud, partner limits. Results are lifecycle states. Fail-open or fail-closed is set per check; a dependency outage parks the instruction as `PENDING_SCREENING` rather than rejecting it.
- **Rules are data, not code paths.** Per-partner profiles (acceptance mode, in-flight limit, runtime pause, allowed currencies and amounts) and per-downstream routing rules live in configuration the pipeline reads, with a reason recorded for every rejection.
- **Routing** chooses the downstream per instruction from currency, partner and capability; no FX inside the engine.
- Partner trend signals feed the bank's existing transaction monitoring: volume and amount against baseline, share of new debtors, debtor refusal rate.

## Processing model

**One service, N identical instances.** One database means a modular monolith, not microservices sharing a schema. Every instance runs every job. Instance roles are split out only when a measured bottleneck says so (API latency breaching its target during processing backlogs).

**Every state with automatic work has a due time.** Each payment row carries `state` and `next_action_at`. There is one kind of work: claim a payment whose due time has passed or that has an unapplied input (provider event, cancellation, approved manual action), apply the input or do the step its state calls for, and write the new state with its new due time.

- Ingestion stores a row due now.
- After sending, due = now + expected response window.
- A downstream event arrives: it is stored in the provider inbox, and the worker that claims the payment applies it through the state machine. Only the lease holder ever changes a payment's state; events, cancellations and approved manual actions are inputs it picks up.
- The event never arrives: the due time passes and the worker queries the downstream. If it reports a later state, the payment is caught up through the state machine; if it reports the same state, the payment keeps waiting for another window; if it cannot find the payment or does not answer (after a few retries), the payment moves to `UNKNOWN`.
- Retries, backoff and request expiry are all just due times. No due time means no automatic work: terminal, or waiting on a person (`UNKNOWN`, `MISMATCH`).

Webhooks and Postgres `LISTEN/NOTIFY` only wake workers sooner. NOTIFY is best effort (lost on disconnect or failover), so the table plus polling is the guarantee; a worker polls immediately whenever it re-establishes LISTEN.

**Claiming.** `SELECT ... FOR UPDATE SKIP LOCKED` in a short transaction sets a lease and increments a lease token. Work happens outside the transaction; no row lock is held across a downstream call. The result is a compare-and-set on the lease token and event sequence (fencing). Every write renews the lease, and every step has a timeout shorter than the lease, so expiry means the worker stopped, and a slow worker cannot overwrite a takeover. A worker that fails does not release its lease: it expires like a crashed worker's, which allows takeover and stops a failing payment being retried in a hot loop. Claims take payments with an unapplied input first, then the earliest due time, with per-partner and per-downstream in-flight limits for fairness.

**Inputs are applied by the lease holder only.** A provider event is stored in the inbox when it arrives (deduplicated on provider and event id) and applied later by the worker that claims the payment. An event for a reference we do not know yet is parked and linked when the reference is stored; a per-reference lock makes sure an event arriving at that moment is never left parked.

**Takeover mid-send never resends blindly.** `SENDING` is recorded with an attempt number before the downstream call. The idempotency key is derived from payment and attempt, never from the worker. An overdue payment in `SENDING` is checked against the downstream before anything else is applied to it, including a cancellation:

- The downstream knows it: store its reference and carry on (a reported state is applied through the state machine).
- The downstream does not know it and we hold no reference: it never arrived, so resend under the same key; the key deduplicates.
- The downstream does not know it but we hold a reference: it accepted it once, so this is not a resend. The payment goes to `UNKNOWN` for reconciliation.
- The downstream does not answer, or its adapter fails: retry the query a few times, then `UNKNOWN`. A timeout never becomes a failure.

**Scheduled singletons** (reconciliation runs, statement matching, partition maintenance) need one runner at a time: ShedLock or db-scheduler recurring tasks.

## State machine

Explicit transition table, pure and side-effect free: a transition returns valid or invalid, and side effects are returned as data. Libraries such as Tinder/StateMachine fit this shape; the need matters, not the library. Handwritten is also fine at this size.

The machine takes a context (state, last known downstream position, cancellation requested) and an input, and returns the new context, one step per state passed through, and the effects of each step. Inputs are the worker's own next step, a provider event, a cancellation request and a response timeout. The downstream position matters: without it `UNKNOWN` and `CANCELLATION_REQUESTED` forget how far a payment got, and a late event cannot be judged.

Out-of-order provider events are resolved by the graph, not the clock:

- Target ahead and reachable: apply. Skipped states are recorded as inferred, and their side effects are still emitted.
- Target behind, or already terminal: record as stale, do not apply.
- No path: recorded with outcome `NO_PATH`, and the payment moves to `MISMATCH`, with an owner and an alert.
- A provider event can only be applied once the payment was sent. It never skips validation, screening or the send itself.
- `MISMATCH` and terminal states never change. A different final outcome reported after a terminal state (`PAID` after `CANCELLED`) is recorded as stale and raises an alert: money moved and a person must look.
- A timeout leads to `UNKNOWN` only, from `SENDING`, `PRESENTED`, `ACCEPTED` and `CANCELLATION_REQUESTED`.

States:

- Main path: `RECEIVED → VALIDATED → PENDING_SCREENING → READY_TO_SEND → SENDING → PRESENTED → ACCEPTED → PAID`
- Side states: `CANCELLATION_REQUESTED`
- Waiting on a person: `UNKNOWN`, `MISMATCH`
- Terminal: `PAID`, `REJECTED`, `REFUSED`, `EXPIRED`, `CANCELLED`

## Downstream systems

One adapter per downstream (anti-corruption layer), one internal state machine, one internal error vocabulary (ISO external status reason codes). The event records the mapped reason code and a link to the raw provider event, which stays in the provider inbox.

The four styles in the brief differ only inside the adapter. All of them converge on the same two things: a send with our idempotency key, and events into the provider inbox.

| Downstream style | Sending | Status and results | Watch for |
|---|---|---|---|
| Synchronous API | The response is the answer | Status query by our key | A timeout after the downstream processed it: ask, never resend blindly |
| Asynchronous with webhooks | The response only acknowledges receipt | Webhooks into the inbox, status query as backstop | Duplicates, reordering, events before we hold the reference |
| File-based | Instructions collected into a file per downstream cut-off | Return files, each line is a provider event | File-level failures, partial acceptance, a whole file arriving late |
| Message-based | Publish with our key as correlation id | Replies on a queue, each is a provider event | At-least-once delivery, replies out of order, poison messages |

- Capability matrix before building an adapter: sync or async, file or message, idempotency key and window, status query, error format, cut-offs, maintenance windows, rate limits, test environment, currencies, delta or absolute writes.
- Our own idempotency key on every call. A timeout means unknown: query status if possible (rules under [Processing model](#processing-model)), otherwise `UNKNOWN` and the reconciliation queue. Unknown never expires into failed. An exception from a status call must never stop a payment: treat it as an unavailable downstream.
- A reference the downstream gives that cannot be stored, or that another payment already holds, sends the payment to `UNKNOWN`; retrying would repeat the same answer.
- Unsolicited events (returns, reversals, late rejects) are flows. The downstream reference is stored when it is learned; unmatched events are parked, never dropped.
- Reconcile against account statements (camt.053/054). A mismatch is a state with an owner.

## Money and time

- Amounts as integer minor units with the currency's ISO 4217 exponent, currency carried end to end, direction explicit rather than a sign. Parse amounts as strings, never floats.
- The requested amount is exact in its currency or rejected. Calculated amounts are rounded once, with a published rule, recorded as an event. Downstreams never round.
- No FX inside the engine. A currency the target downstream does not support is rejected at validation.
- Timezone-aware storage, injected clock, explicit business calendar and zone for cut-offs and expiry.

## Cancellation

Per instruction. Not yet sent: cancelled at once, and never sent. In flight (`SENDING`, `PRESENTED`, or `UNKNOWN` because it may still be in flight): state `CANCELLATION_REQUESTED` and the cancellation is forwarded downstream. The race with debtor acceptance has an explicit outcome:

- A `PRESENTED` acknowledgement arriving after the request does not undo it; only the known position moves.
- If the debtor accepts first, the payment follows to `ACCEPTED` and the cancellation lost.
- Only a cancellation we asked for can be confirmed as `CANCELLED`, and not once the debtor accepted; otherwise it is a `MISMATCH`.
- A request that arrives too late (accepted, paid, already requested) must be answered, not dropped: add a partner-visible refusal status when the vocabulary is frozen.

Undoing an executed payment is a separate refund or return flow.

## Data, availability and scale

- **Volume.** A file of thousands of instructions is split into individual rows in bounded transactions, so one huge file never holds a long transaction, and processing then runs in parallel across workers. Ingestion stays available while processing is saturated.
- Zero data loss: acknowledge only after commit replicated to a synchronous standby. If the standby is unreachable, stop acknowledging; partners resend.
- Queueing and graceful degradation, not burst scaling.
- Limits are the database primary and downstream capacity, not compute. Partition append-only tables by time from day one; buy disk until restore time or procurement says otherwise; archiving is a partition detach.
- Sharding is far off. If ever: partner as the tenant key, never the downstream (unknown at ingestion, uneven, and partner queries would scatter).

## Controls and operations

- Maker-checker for every state-changing manual action, with a reason.
- Infrastructure as code and GitOps; pull request approval is maker-checker for platform changes; signed artifacts and SBOM; logged break-glass; keys in KMS/HSM.
- Containers on the bank's platform, cloud or self-hosted; portability is the exit strategy.
- Structured logs with correlation ids and an allowlist of loggable fields (no payment data). Business audit is `payment_event`; the security audit log is separate and tamper-evident.
- Alerts only when someone has something to do, each with an owner and runbook: oldest non-terminal item per state, unknown queue age, ingestion stalled, per-partner rejection spike, downstream circuit open, webhook delivery failing, reconciliation mismatch. Business rejections stay out of technical failure ratios. Runtime per-partner pause.
- Public documentation where the status vocabulary is a contract ("accepted" means accepted for processing; cancellation is best effort; rounding rules). Legal reviews the wording. Published status values are forever.

## Delivery

- Step 0, blocking: canonical model, transition table, event schema, status vocabulary, API and file contracts.
- Parallel streams: ingestion, core lifecycle, first downstream with simulator, partner-facing status and docs, controls, platform, risk, reconciliation.
- Shared definition of done: failure scenarios tested, audit events emitted, alerts with runbook, docs, security review, deployed through the pipeline.
- Tests: developers own coverage. Non-negotiable scenarios: illegal transitions, duplicates, out-of-order events, timeouts, partial failure, resubmitted files, amount invariants. Real local Postgres, partner stubs, misbehaving downstream simulators, contract tests against downstream test environments, CI asserts the test count. Two techniques worth keeping: a sequence explorer that applies every input sequence to a depth and checks invariants on the state machine, and mutation checks (break a safeguard on purpose, confirm a test fails).
- Rollout: one pilot partner and one vetted downstream, parallel run against the existing process compared per instruction with an exit criterion stated up front, per-partner routing as rollback, then batches.

## Evidence from the proof of concept

The core loop was built and exercised against a real PostgreSQL: the state machine, ingestion with deduplication, the worker with lease and fenced writes, the provider inbox, and one fake downstream that can delay, duplicate, reorder, reject and time out. It shows the recommended design holds under the failures that matter, and it changed the recommendation in these places:

- **Duplicate submission, out-of-order events, timeout to `UNKNOWN`, takeover mid-send and amount invariants** all behave as specified, including with several workers racing. Each safeguard was checked by breaking it on purpose.
- **The state machine needs a context.** A bare state forgot how far a payment got after a timeout or a cancellation; the downstream position and the cancellation flag fixed that.
- **Takeover needs a status check, and a stored reference changes the answer.** Resending after the downstream accepted a payment would create a second one; that case now goes to `UNKNOWN`.
- **Timeouts must ask before giving up,** or a lost webhook leaves a paid payment in `UNKNOWN`.
- **Events and references race.** An event arriving as its reference is stored could be left parked forever; a per-reference lock fixes it.
- **Concurrent resends of the same instructions deadlocked** until rows were inserted in a fixed order.
- **Every effect belongs in the outbox,** not only partner statuses, so that a worker dying after commit cannot lose a cancellation forward, a reconciliation request or an alert.

Independent code reviews of the PoC found each of these before it could matter. What the PoC does not cover, and the known gaps in it, are in [poc-limits.md](poc-limits.md).

## Risks and open questions

- **Response windows.** One window cannot serve both silent-downstream detection in `SENDING` and a debtor's decision time in `PRESENTED`. Set them per state from the downstream's real behaviour.
- **Too-late cancellation** needs a partner-visible status, which is a contract decision.
- **Source timestamps and raw provider messages** are needed for audit and must be kept from day one.
- **Claim query performance** needs a rewrite that finds due rows and rows with inputs separately before any load test.
- **Reconciliation and manual resolution** of `UNKNOWN` and `MISMATCH` are where operations will live day to day; design them with operations early.
- **Front doors, validation rules and screening** are scoped by the questions at the top, and need partners and the bank's risk functions.
