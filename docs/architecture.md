# Architecture proposal

![Architecture](architecture.png)

> **Status.** The core loop below was built as a proof of concept (state machine, worker with lease and fencing, a fake downstream, scenario tests against a real Postgres) and checked by independent reviews. This version reflects what it proved. The diagram predates the PoC and does not show the changes listed next. What the PoC leaves out is in [poc-limits.md](poc-limits.md); the data model is in [database.md](database.md).

## What the PoC changed

- **The state machine needs a context, not only a state.** It also carries the last known downstream position (`SENDING`, `PRESENTED`, `ACCEPTED`) and whether a cancellation was requested. Without them `UNKNOWN` and `CANCELLATION_REQUESTED` forget how far a payment got, and a late event cannot be judged. Two columns on `payment_request` hold them.
- **Timeouts ask before they give up.** When the response window passes, the worker queries the downstream's status first. A downstream that moved on without telling us (a lost webhook) is caught up; one that still reports the same status keeps the payment waiting; only silence leads to `UNKNOWN`. A single unanswered query is retried a few times first.
- **A takeover never resends once the downstream has accepted.** A stored downstream reference is proof of acceptance. See takeover mid-send under [Processing model](#processing-model).
- **Every effect is an outbox row.** Not only partner statuses: forwarding a cancellation, queueing reconciliation and raising an alert are written in the same transaction too, so a worker that dies after committing cannot lose them.
- **Only some states are partner-visible.** Internal states (validation, screening, sending, `UNKNOWN`, `MISMATCH`) never reach a partner, because published statuses are forever.
- **Answers nobody can act on go to a person.** A downstream reference that cannot be stored, or a payment the downstream cannot find after accepting it, ends in `UNKNOWN` rather than being retried forever.
- **Open design gaps** found by the reviews are listed in [poc-limits.md](poc-limits.md): the partner is not told a too-late cancellation was refused (it needs a new status), one response window serves all waiting states, and source timestamps and raw provider messages are not kept.

## Questions first

The design changes less with the answers than the front doors do, but these decide scope.

1. **What drives the design?** Do partners want real time, or is the change internal? What happens today, and is any of it automated?
2. **Scope.** One-time payments only, or payment plans too? Bills only, or online checkout as well? Alongside existing e-invoicing and direct debit schemes, or an alternative to them?
3. **Files.** Do partners already export these files? Is there an existing host-to-host channel to reuse?
4. **Constraints.** Which currencies, downstream systems and SLAs are in scope?

pain.013 is a Creditor Payment Activation Request (Request to Pay). The status goes back as pain.014, and an accepted request is followed by a separate credit transfer, possibly several or a partial one. The final status waits on the debtor, so status is asynchronous in both phases: Phase 2 removes batching, not asynchrony.

## Recommendation: one core, two front doors

Every submission becomes individual instructions processed by one pipeline. Files and the API are two ingestion adapters into the same core.

Which front doors exist depends on question 1:

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

## Processing model

**One service, N identical instances.** One database means a modular monolith, not microservices sharing a schema. Every instance runs every job. Instance roles are split out only when a measured bottleneck says so (API latency breaching its target during processing backlogs).

**Every state with automatic work has a due time.** Each payment row carries `state` and `next_action_at`. There is one kind of work: claim a payment whose due time has passed or that has an unapplied input (provider event, cancellation, approved manual action), apply the input or do the step its state calls for, and write the new state with its new due time.

- Ingestion stores a row due now.
- After sending, due = now + expected response window.
- A webhook arrives: it is stored in the provider inbox, and the worker that claims the payment applies it through the state machine. Only the lease holder ever changes a payment's state; webhooks, cancellations and approved manual actions are inputs it picks up.
- The webhook never arrives: the due time passes and the worker queries the downstream. If it reports a later state, the payment is caught up through the state machine; if it reports the same state, the payment keeps waiting for another window; if it cannot find the payment or does not answer (after a few retries), the payment moves to `UNKNOWN`.
- Retries, backoff and request expiry are all just due times. No due time means no automatic work: terminal, or waiting on a person (`UNKNOWN`, `MISMATCH`).

Webhooks and Postgres `LISTEN/NOTIFY` only wake workers sooner. NOTIFY is best effort (lost on disconnect or failover), so the table plus polling is the guarantee; a worker polls immediately whenever it re-establishes LISTEN.

**Claiming.** `SELECT ... FOR UPDATE SKIP LOCKED` in a short transaction sets a lease and increments a lease token. Work happens outside the transaction; no row lock is held across a downstream call. The result is a compare-and-set on the lease token and event sequence (fencing). Every write renews the lease, and every step has a timeout shorter than the lease, so expiry means the worker stopped, and a slow worker cannot overwrite a takeover. A worker that fails does not release its lease: it expires like a crashed worker's, which allows takeover and stops a failing payment being retried in a hot loop. Claims take payments with an unapplied input first, then the earliest due time. Per-partner and per-downstream in-flight limits for fairness are not built yet.

**Inputs are applied by the lease holder only.** A provider event is stored in the inbox when it arrives (deduplicated on provider and event id) and applied later by the worker that claims the payment. An event for a reference we do not know yet is parked and linked when the reference is stored; a per-reference lock makes sure an event arriving at that moment is never left parked.

**Takeover mid-send never resends blindly.** `SENDING` is recorded with an attempt number before the downstream call. The idempotency key is derived from payment and attempt, never from the worker. An overdue payment in `SENDING` is checked against the downstream before anything else is applied to it, including a cancellation:

- The downstream knows it: store its reference and carry on (a reported state is applied through the state machine).
- The downstream does not know it and we hold no reference: it never arrived, so resend under the same key; the key deduplicates.
- The downstream does not know it but we hold a reference: it accepted it once, so this is not a resend. The payment goes to `UNKNOWN` for reconciliation.
- The downstream does not answer, or its adapter fails: retry the query a few times, then `UNKNOWN`. A timeout never becomes a failure.

**Scheduled singletons** (reconciliation runs, statement matching, partition maintenance) need one runner at a time: ShedLock or db-scheduler recurring tasks.

## State machine

Explicit transition table, pure and side-effect free: a transition returns valid or invalid, and side effects are returned as data. Libraries such as Tinder/StateMachine fit this shape; the need matters, not the library.

Out-of-order provider events are resolved by the graph, not the clock:

- Target ahead and reachable: apply. Skipped states are recorded as inferred, and their side effects are still emitted.
- Target behind, or already terminal: record as stale, do not apply.
- No path: recorded with outcome `NO_PATH`, and the payment moves to `MISMATCH`, with an owner and an alert.

The machine takes a context (state, last known downstream position, cancellation requested) and an input, and returns the new context, one step per state passed through, and the effects of each step. Inputs are the worker's own next step, a provider event, a cancellation request and a response timeout. Rules the PoC settled:

- A provider event can only be applied once the payment was sent. For a payment that was never sent it is a `NO_PATH`: events never skip validation, screening or the send itself.
- Events are judged against the downstream position, so a late event after `UNKNOWN` is stale or applied relative to where the payment got to, never relative to a guess.
- `MISMATCH` and terminal states never change. A different final outcome reported after a terminal state (`PAID` after `CANCELLED`) is recorded as stale and raises an alert: money moved and a person must look.
- A timeout leads to `UNKNOWN` only, from `SENDING`, `PRESENTED`, `ACCEPTED` and `CANCELLATION_REQUESTED`.

States:

- Main path: `RECEIVED → VALIDATED → PENDING_SCREENING → READY_TO_SEND → SENDING → PRESENTED → ACCEPTED → PAID`
- Side states: `CANCELLATION_REQUESTED`
- Waiting on a person: `UNKNOWN`, `MISMATCH`
- Terminal: `PAID`, `REJECTED`, `REFUSED`, `EXPIRED`, `CANCELLED`
- Partner-visible: `PRESENTED`, `ACCEPTED`, `CANCELLATION_REQUESTED` and the terminal states. `RECEIVED` is acknowledged at ingestion, not by a transition.

## Validation and checks

- Synchronous at ingestion: schema, required fields, duplicate ids, known partner, per-partner usage profile. Unsupported message versions get a clear non-retryable error.
- Asynchronous before routing: sanctions, AML, fraud, partner limits. Results are lifecycle states. Fail-open or fail-closed is set per check; a dependency outage parks the instruction as `PENDING_SCREENING` rather than rejecting it.
- Partner trend signals feed the bank's existing transaction monitoring: volume and amount against baseline, share of new debtors, debtor refusal rate.

## Downstream systems

- One adapter per downstream (anti-corruption layer), one internal state machine, one internal error vocabulary (ISO external status reason codes). The event records the mapped reason code and a link to the raw provider event, which stays in the provider inbox.
- Capability matrix before building an adapter: sync or async, file or message, idempotency key and window, status query, error format, cut-offs, maintenance windows, rate limits, test environment, currencies, delta or absolute writes.
- Our own idempotency key on every call. A timeout means unknown: query status if possible (the rules are under Processing model), otherwise `UNKNOWN` and the reconciliation queue. Unknown never expires into failed. The adapter boundary must never let an exception from a status call stop a payment: the worker treats it as an unavailable downstream.
- Unsolicited events (returns, reversals, late rejects) are flows. The downstream reference is stored when sending; unmatched events are parked, never dropped.
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
- A request that arrives too late (accepted, paid, already requested) is recorded as stale. Telling the partner it was refused needs a new status and is not built yet.

Undoing an executed payment is a separate refund or return flow.

## Data, availability and scale

- Zero data loss: acknowledge only after commit replicated to a synchronous standby. If the standby is unreachable, stop acknowledging; partners resend.
- Queueing and graceful degradation, not burst scaling. Ingestion stays available while processing is saturated.
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
- Tests: developers own coverage. The PoC added two techniques worth keeping: a sequence explorer that applies every input sequence to a depth and checks invariants on the state machine, and mutation checks (break a safeguard on purpose, confirm a test fails). Non-negotiable scenarios: illegal transitions, duplicates, out-of-order events, timeouts, partial failure, resubmitted files, amount invariants. Real local Postgres, partner stubs, misbehaving downstream simulators, contract tests against downstream test environments, CI asserts the test count.
- Rollout: one pilot partner and one vetted downstream, parallel run against the existing process compared per instruction with an exit criterion stated up front, per-partner routing as rollback, then batches.
