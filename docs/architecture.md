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

The brief leaves payment types, schemes and downstream systems open on purpose, so this is a general engine. A scheme's own lifecycle and status codes are mapped onto the internal ones inside its adapter. Request to Pay is the worked example throughout because pain.013 is one.

## Recommendation in brief

- **One core, two front doors.** Every submission becomes individual instructions processed by one pipeline. Files and the API are two ingestion adapters into the same core, so Phase 2 adds a door rather than a second engine.
- **One modular service, N identical instances, one PostgreSQL.** Every state with automatic work has a due time. A worker claims what is due or has a new input, under a lease with fenced writes. No queue broker, no per-state services.
- **An explicit, pure state machine.** Out-of-order downstream events are resolved by the state graph, never by clocks. Unknown outcomes are a state with an owner; a timeout never becomes a failure.
- **A downstream outage is not an unknown outcome.** The worker backs off and retries on its own; only an outage longer than a set horizon hands payments to a person.
- **Everything the engine does is an event, and every other effect is an outbox row,** written in the same transaction as the state change. The downstream call itself is guarded by recording `SENDING` before it is made. Nothing is lost if a worker dies, and every status can be explained.
- **Downstreams hide behind adapters.** One adapter per downstream, one internal vocabulary, our own idempotency key on every call.
- **Build the failure paths first.** Duplicates, reordering, timeouts and takeovers decide whether this engine is trustworthy; the happy path is the easy part.

## Questions first

The design changes less with the answers than the front doors do, but these decide scope.

1. **What drives the design?** Do partners want real time, or is the change internal? What happens today, and is any of it automated?
2. **Scope.** One-time payments only, or payment plans too? Bills only, or online checkout as well? Alongside existing e-invoicing and direct debit schemes, or an alternative to them?
3. **Files.** Do partners already export these files? Is there an existing host-to-host channel to reuse?
4. **Constraints.** Which currencies, downstream systems and SLAs are in scope?

pain.013 is a Creditor Payment Activation Request (Request to Pay). The status goes back as pain.014, and an accepted request is followed by a separate credit transfer, possibly several or a partial one. The final status waits on the debtor and on settlement, so status is asynchronous in both phases: Phase 2 removes batching, not asynchrony.

## One core, two front doors

Every submission becomes individual instructions processed by one pipeline. Which front doors exist depends on question 1:

- Partners drive files: files are a permanent channel and the API is added beside it, never a forced migration.
- Partners want an API, or nobody produces files yet: go straight to the API, with a bulk endpoint.
- Rejected: a temporary file channel partners are later moved off. Partners see a forced migration as a downgrade, and anything given to partners is supported for years.

### File vs API

Granularity is not the difference: status, webhooks and cancellation are per instruction either way. The API adds feedback inside the partner's own call, lower latency, and fine-grained access (keys scoped to submit or cancel). Cancellation is richest through the API, but file partners can cancel too (see [Acknowledgements and status](#acknowledgements-and-status)). Files fit existing ERP exports, are efficient in bulk, and the file itself is an audit record.

A file is a bulk API call with the same idempotency rules:

- Deduplicate per instruction (partner + end-to-end id). A corrected resend gets a new message id; already-accepted rows are no-ops.
- Same id with different content is a conflict, rejected explicitly. Never a silent edit.
- A message id names one submission. The same message id with the same file hash is a duplicate and gets the original acknowledgement; with a different hash the whole message is rejected as a conflict, never merged into the earlier one.
- A file whose hash, or count plus control sum, matches a recent file from the same partner is held as a possible duplicate, because a re-exported file can carry fresh ids. A per-partner rule can also warn or reject on the same debtor, amount and remittance reference within a time window.
- End-to-end ids are unique per partner for good, and this is a published rule: every new instruction needs a new id. The deduplication key outlives archiving, so an old id never becomes free again.
- Control totals validate the file as received; the report gives accepted and rejected counts against them.
- Per-partner setting: partial acceptance (default) or all-or-nothing.

### Out of scope

Payment plans and mandates. They bring due-date rules, missed payments, under- and overpayment, and possibly consumer-credit regulation. That logic belongs to the biller or a separate product. The engine keeps an optional parent reference on each instruction and emits status events rich enough to reconcile a plan against.

## Acknowledgements and status

The partner needs an acknowledgement and processing status. These are different things at three levels:

1. **Receipt.** The API answers inside the call: accepted for processing, or a clear, non-retryable error. A file gets a file-level acknowledgement with accepted and rejected counts against its control totals, and a reason per rejected row.
2. **Per-instruction status.** Each partner-visible state change produces a status the partner can receive. Delivery channels, all fed from the same outbox: webhooks, a status API the partner can poll, and pain.014 files for file partners. Delivery is at least once with a stable event id per status, so partners deduplicate. A partner polling gets the current state, so a missed webhook does not matter. A webhook URL must be https to a fully qualified host name whose every address is public, checked at registration and before each delivery; delivery connects to the checked addresses and follows no redirects, so a partner cannot point the engine inside the bank ([threat model](threat-model.md), B3).
3. **Final outcome.** For a Request to Pay the final status waits on the debtor and on the credit transfer that follows, so it arrives later and asynchronously, in both phases.

The status vocabulary is a contract, and published values are forever. Expose only states that mean something to the partner: `PRESENTED`, `ACCEPTED`, `CANCELLATION_REQUESTED`, `PAID`, `SETTLED`, `REJECTED`, `REFUSED`, `EXPIRED`, `CANCELLED` (`RECEIVED` is acknowledged at receipt). Keep validation, screening, sending, `UNKNOWN` and `MISMATCH` internal. "Accepted" must be documented as accepted for processing, not as paid.

Before the vocabulary is frozen it gains `NOT_PAID` (accepted, but no money by the deadline), `PAID_LATE` (money arrived after a not-paid final state), `RETURNED`, and a refusal for a cancellation that came too late. A payment under review (`UNKNOWN`, `MISMATCH`, `SCREENING_REVIEW`, `QUARANTINED`) keeps its last status but carries an under-review flag with the date it started, and the time limit for resolving it is published.

**Partners can order and reconcile what we send.** Every status carries the payment's event sequence, and partners keep the highest. A per-partner cursor feeds a "changes since" endpoint, and a daily report gives counts and values per status. Webhook deliveries are signed (HTTP Message Signatures, keys in KMS, rotated with overlap). Event ids are globally unique values written with the row, never derived from a sequence a restore could reuse.

**File partners can cancel too.** The file channel accepts per-instruction cancellations (camt.055 style) and "cancel everything not yet final in this submission", through the same cancellation input, answered with camt.029. The mapping of each status onto pain.014 status and reason codes is part of Step 0.

Paid and settled are separate. Some payout platforms report a payment as paid once it reaches the payee's bank, and it can still be rejected and returned after that. `PAID` means the downstream reports the payment made. `SETTLED` means reconciliation against the partner's account statement confirmed the money arrived, for the full amount. Money can still come back after `PAID`, so only `SETTLED` is final. A partner that acts on `PAID` (ships goods) takes that risk knowingly.

## Internal representation

Parse and validate at the edge, then work on one canonical instruction. Channel formats (pain.013 XML in a file, JSON in the API) and downstream formats never reach the core.

- **Canonical instruction.** Partner and message references, end-to-end id, amount as integer minor units plus ISO 4217 currency, the amount-modification terms and charge bearer, debtor and creditor identification, remittance reference, requested execution date, expiry, optional parent reference, and a content hash for conflict detection.
- **One mapping in, one per downstream out.** Ingestion adapters map the channel format to the canonical instruction; downstream adapters map it to the downstream's format and its replies back to the internal status vocabulary and reason codes.
- **Keep the original.** The received file stays in write-once object storage, with its hash recorded at receipt, and is the audit record for what the partner sent. It is kept for the retention period (see [Retention and erasure](#retention-and-erasure)).
- **Version the model and the contracts.** Unsupported message versions get a clear non-retryable error.
- **Parse safely.** XML is parsed with DTDs and external entities switched off, validated against the pinned schema, and streamed with depth and element-count limits. Rejection reasons never echo values the partner sent.

## Validation and business rules

- **Synchronous at ingestion:** schema, required fields, duplicate ids, known partner, per-partner usage profile, amount parsed exactly (see [Money and time](#money-and-time)).
- **Asynchronous before routing:** sanctions, AML, fraud, partner limits. Results are lifecycle states. Sanctions screening always fails closed: nothing is sent without a result. Other checks can be set to fail open or closed. A dependency outage parks the instruction as `PENDING_SCREENING` rather than rejecting it. A potential match waits in `SCREENING_REVIEW` for a two-person decision, with an owner. Compliance rejections reach the partner with one generic reason code that compliance signs off, so a rejection never discloses a suspicion. Per-partner value limits are reserved atomically, not checked by each worker on its own.
- **Rules are data, not code paths.** Per-partner profiles (acceptance mode, in-flight limit, runtime pause, allowed currencies and amounts) and per-downstream routing rules live in configuration the pipeline reads, with a reason recorded for every rejection.
- **Routing** chooses the downstream per instruction from currency, partner and capability; no FX inside the engine.
- Partner trend signals feed the bank's existing transaction monitoring: volume and amount against baseline, share of new debtors, debtor refusal rate.

## Processing model

**One service, N identical instances.** One database means a modular monolith, not microservices sharing a schema. Every instance runs every job. Instance roles are split out only when a measured bottleneck says so (API latency breaching its target during processing backlogs).

**Every state with automatic work has a due time.** Each payment row carries `state` and `next_action_at`. There is one kind of work: claim a payment whose due time has passed or that has an unapplied input (provider event, cancellation, approved manual action), apply the input or do the step its state calls for, and write the new state with its new due time.

- Ingestion stores a row due now.
- After sending, due = now + expected response window.
- A downstream event arrives: it is stored in the provider inbox, and the worker that claims the payment applies it through the state machine. Only the lease holder ever changes a payment's state; events, cancellations and approved manual actions are inputs it picks up.
- The event never arrives: the due time passes and the worker queries the downstream. If it reports a later state, the payment is caught up through the state machine; if it reports the same state, the payment keeps waiting for another window; if it cannot find a payment it has already acknowledged, the payment moves to `UNKNOWN` (a send it never received is resent, see below). If it does not answer, that is an outage (see [Downstream outages](#downstream-outages)).
- Retries, backoff and request expiry are all just due times. No due time means no automatic work: terminal, waiting on settlement (`PAID`), or waiting on a person (`UNKNOWN`, `MISMATCH`).

Webhooks and Postgres `LISTEN/NOTIFY` only wake workers sooner. NOTIFY is best effort (lost on disconnect or failover), so the table plus polling is the guarantee; a worker polls immediately whenever it re-establishes LISTEN. NOTIFY is never sent inside a state-change or ingestion transaction: under synchronous replication it makes every notifying commit wait for the previous one's standby round trip. Wake-ups come from a separate session with `synchronous_commit` off.

**Claiming.** `SELECT ... FOR UPDATE SKIP LOCKED` in a short transaction sets a lease and increments a lease token. Work happens outside the transaction; no row lock is held across a downstream call. The result is a compare-and-set on the lease token and event sequence (fencing). Every write renews the lease, and every downstream call and database statement has a timeout shorter than the lease, checked when the service starts (a commit's wait for the standby is not covered by a statement timeout and is bounded on the client; see [Data, availability and scale](#data-availability-and-scale)), so expiry means the worker stopped, and a slow worker cannot overwrite a takeover. Leases and due times use the database clock, not each instance's own. A worker that fails does not release its lease: it expires like a crashed worker's, which allows takeover and stops a failing payment being retried in a hot loop. Claims take payments with an unapplied input first, then the earliest due time. Fairness is chosen before the row: a claim picks a lane and a partner with due work in turn, then that partner's earliest due payment, and skips downstreams and partners that are paused or have an open circuit. "In flight" means leased right now, enforced with a per-partner counter row; a request waiting days on a debtor does not count. Interactive work gets its own claim lane, so one API request is never queued behind every step of a large file.

**Poison payments are quarantined.** Each claim that ends in an exception increments a failure count on the row, reset on success, and the next claim backs off on it. After a set number of failures the payment moves to `QUARANTINED` for a person, with the exception class and an alert, instead of retrying every lease period.

**Operators can stop sends.** A per-downstream pause stops new sends while status queries continue, and during a downstream's declared maintenance window its outage horizon is frozen. A global kill switch stops all sends. Both are runtime settings with maker-checker and audit.

**Backpressure.** The age of the oldest unleased overdue payment per lane is alerted on. When the backlog would take longer than a set time to drain, the API answers 429 with `Retry-After` and file acknowledgements say processing is delayed.

**Inputs are applied by the lease holder only.** A provider event is stored in the inbox when it arrives (deduplicated on provider and event id) and applied later by the worker that claims the payment. An event for a reference we do not know yet is parked and linked when the reference is stored; a per-reference lock makes sure an event arriving at that moment is never left parked.

**Takeover mid-send never resends blindly.** `SENDING` is recorded with an attempt number before the downstream call. The idempotency key is derived from the partner, the end-to-end id and the attempt, never from the worker or a random id, so it survives a database restore. An overdue payment in `SENDING` is checked against the downstream before anything else is applied to it, including a cancellation:

- The downstream knows it: store its reference and carry on (a reported state is applied through the state machine).
- The downstream does not know it and we hold no reference: it never arrived, so resend under the same key; the key deduplicates. This holds only while the downstream still keeps the key, so the outage horizon must stay inside its key retention.
- The downstream does not know it but we hold a reference: it accepted it once, so this is not a resend. The payment goes to `UNKNOWN` for reconciliation.
- The downstream does not answer, or its adapter fails: an outage. Back off and retry; `UNKNOWN` only once the outage outlasts the horizon. A timeout never becomes a failure.

### Downstream outages

When a downstream is down, every payment that waits on it fails its next call. Treating each failure as an unknown outcome sent every payment in the outage to a person: in the proof of concept, all 11 payments caught in a 30-minute outage ended in `UNKNOWN`. Options considered:

| Option | What it does | Verdict |
|---|---|---|
| Back off per payment, up to a horizon | Failed sends and unanswered status queries keep the payment in its state; the wait doubles from 30 seconds to a 10-minute limit, with jitter so payments caught in the same outage don't retry in lockstep; only an outage longer than the horizon (2 hours by default) leads to `UNKNOWN`; a status answer that finds the payment, or any state change, ends an outage | Chosen and built, except the jitter. Removes the manual work for any outage shorter than the horizon, with no new state and no shared state |
| Circuit breaker per downstream | Stops new sends and pauses retries while the downstream is known to be down; probes, then resumes with a warm-up | Next step. Saves load and failed calls, but on its own does not resolve anything |
| `UNKNOWN` re-checks itself | `UNKNOWN` gets a long backoff and asks the downstream again before a person looks | Worth adding later as the safety net after the horizon. Changes what `UNKNOWN` means, and needs a transition back to the waiting state |
| Bulk reconciliation after recovery | Matches a downstream report or statement against everything affected | Belongs to reconciliation; needed for downstreams that cannot answer status queries |

With backoff, the same 30-minute outage needs nobody: lost webhooks are recovered by asking, sends that never arrived are resent under the same key, and each payment makes at most 8 failed attempts. The horizon is per downstream and must be shorter than its idempotency key retention, because a resend after the outage relies on the key still being known. A send that failed while the downstream answers status queries with "not found" is retried the same way: the downstream says it never arrived.

**Warm-up after an outage.** Jitter spreads retries out, but once a downstream is back every waiting payment still arrives within one backoff window, on a service with cold caches and connection pools. The circuit breaker therefore resumes slowly: open (nothing sent), half-open (a few probes), then a per-downstream rate limit that starts low and climbs to normal, shared by retries and new sends. Waiting payments go first, oldest first, so those nearest the horizon are served before they reach a person. The warm-up counts against the horizon, so the horizon needs headroom for it.

How each failure recovers, with and without a downstream status endpoint, and the full list of what still needs a person, are in [recovery.md](recovery.md).

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
- A different outcome after `PAID` (a reject, a refusal, a cancellation) means money came back or never should have moved: the payment goes to `MISMATCH`. Returns become their own partner-visible status when the vocabulary is frozen.
- A timeout leads to `UNKNOWN` only, from `SENDING`, `PRESENTED` and `CANCELLATION_REQUESTED`. `ACCEPTED` is different: the debtor said yes, so silence is a business outcome, not a lost one.
- After acceptance the deadline is the requested execution date plus a set number of business days. At the deadline the payment moves to the partner-visible `NOT_PAID`, not to `UNKNOWN`. Statement matching can move `ACCEPTED` straight to `SETTLED`.
- Expiry is checked before every send. At expiry the cancellation is forwarded, and `EXPIRED` is final once the downstream confirms it or a grace period passes.
- The not-paid final states (`EXPIRED`, `REFUSED`, `CANCELLED`, `NOT_PAID`) are final unless money arrives: a matched credit moves the payment to the partner-visible `PAID_LATE`, and settlement follows as usual. A return after `SETTLED` is a linked return record with its own status, so `SETTLED` itself never changes.
- **Changing the machine while payments are in flight: expand, then contract.** A release learns to read a new state, input or status one release before anything writes it, and writing is switched on once no older instance runs. A rollback goes only to a version that reads everything already written. Each event records the transition-table version. Schema constraints are added `NOT VALID` and then validated, with a lock timeout.

States:

- Main path: `RECEIVED → VALIDATED → PENDING_SCREENING → READY_TO_SEND → SENDING → PRESENTED → ACCEPTED → PAID → SETTLED`
- Side states: `CANCELLATION_REQUESTED`
- Waiting on settlement: `PAID`. It has no timer: reconciliation confirms it, and an old `PAID` is an alert.
- Waiting on a person: `UNKNOWN`, `MISMATCH`, `SCREENING_REVIEW`, `QUARANTINED`
- Terminal: `SETTLED`, `REJECTED`; final unless money arrives: `REFUSED`, `EXPIRED`, `CANCELLED`, `NOT_PAID` (then `PAID_LATE`)

## Downstream systems

One adapter per downstream (anti-corruption layer), one internal state machine, one internal error vocabulary (ISO external status reason codes). The event records the mapped reason code and a link to the raw provider event, which stays in the provider inbox.

The aim is for the four styles in the brief to differ only inside the adapter, converging on the same two things: a send with our idempotency key, and events into the provider inbox. Only the synchronous style was built. A file-based downstream probably needs its own batch entity, because a file has a state, a cut-off and a whole-file reject of its own. A message-based adapter must not treat a publish acknowledgement as acceptance, or a stored reference stops meaning "the downstream has it".

| Downstream style | Sending | Status and results | Watch for |
|---|---|---|---|
| Synchronous API | The response is the answer | Status query by our key | A timeout after the downstream processed it: ask, never resend blindly |
| Asynchronous with webhooks | The response only acknowledges receipt | Webhooks into the inbox, status query as backstop | Duplicates, reordering, events before we hold the reference |
| File-based | Instructions collected into a file per downstream cut-off | Return files, each line is a provider event | File-level failures, partial acceptance, a whole file arriving late |
| Message-based | Publish with our key as correlation id | Replies on a queue, each is a provider event | At-least-once delivery, replies out of order, poison messages |

- Capability matrix before building an adapter: sync or async, file or message, idempotency key and window, status query, error format, cut-offs, maintenance windows, rate limits, test environment, currencies, delta or absolute writes.
- The idempotency key carries the takeover rules, so pin down its semantics with the downstream's owners and test them in its test environment: how long a key is kept (it sets the outage horizon), what a repeated key returns (it must be the original result, never a reject: a "duplicate" reject would tell the partner `REJECTED` for a payment that went through), whether status can be queried by our key, and its format. ISO 20022 identifiers are at most 35 characters, so the adapter maps our key to the downstream's format.
- Our own idempotency key on every call, derived from the partner, the end-to-end id and the attempt and hashed into the downstream's format. A timeout means unknown: query status if possible (rules under [Processing model](#processing-model)), otherwise `UNKNOWN` and the reconciliation queue. Unknown never expires into failed. An exception from a status call must never stop a payment: treat it as an unavailable downstream.
- A reference the downstream gives that cannot be stored, or that another payment already holds, sends the payment to `UNKNOWN`; retrying would repeat the same answer.
- Unsolicited events (returns, reversals, late rejects) are flows. The downstream reference is stored when it is learned; unmatched events are parked, never dropped.
- **Bound at first send, drained before retirement.** A payment is bound to its downstream when it is first sent and never re-routed after `SENDING`, so a routing rollback applies to new payments only. A downstream is retired by stopping new sends and draining it; its adapter keeps answering status queries, returns and recalls until its return window closes. Currency support is checked against the downstream actually chosen.

### Reconciliation

Reconciliation against account statements (camt.053/054) is what confirms `SETTLED`.

- **Reading the partner's statements is a condition of onboarding.** A partner whose collection account we cannot see gets `PAID` as its last status, and its contract says so.
- **Matching order:** the end-to-end id; then the creditor reference plus amount plus debtor; then a person. Our key and the downstream reference do not travel on the debtor's credit transfer.
- **Each credit matches one request only.** Credits that match none are reported to the partner and queued with an owner.
- **Deadlines are business days.** A `PAID` payment not settled by its deadline raises an alert to the owner of that kind of break.

## Money and time

- Amounts as integer minor units with the currency's ISO 4217 exponent, currency carried end to end, direction explicit rather than a sign. Parse amounts as strings, never floats.
- The requested amount is exact in its currency or rejected. Calculated amounts are rounded once, with a published rule, recorded as an event. Downstreams never round.
- **Settlement keeps a running total.** Every matched credit is an event with its amount. `SETTLED` is reached when the total received meets the request's amount rule: the exact amount, or within the request's amount-modification terms, with a per-route tolerance for charges intermediaries deduct. A total that stays short past the deadline, or goes over, is a mismatch with an owner.
- Each amount stores its currency's exponent with it, so a change in a currency table never rescales a stored amount. A file's control sum is an exact decimal, because ISO sums amounts across currencies.
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

- **Volume.** A file of thousands of instructions is stored first, then split into individual rows in bounded chunks, so one huge file never holds a long transaction, and processing then runs in parallel across workers. The file has its own state, which is how all-or-nothing acceptance works once one transaction no longer covers the file. The proof of concept ingests a submission in one transaction. Ingestion stays available while processing is saturated.
- **No acknowledged data is lost.** Two synchronous-capable standbys with quorum commit (`ANY 1`), one in another zone, so losing one standby does not stop writes. A failover manager with fencing promotes only a synchronous standby. A commit's wait for the standby ignores `statement_timeout`, and cancelling that wait reports success for a row that may not be replicated, so commits are bounded by a client-side timeout, a timed-out commit is "outcome unknown", never success, and the runbook forbids cancelling those waits. Ingestion acknowledges only a commit that returned normally; an unknown outcome is settled by the partner's resend, which deduplication makes safe.
- **Recovery targets and restore.** RPO is zero for losing a node or a zone; the RPO and RTO for losing a region are set with the bank's continuity owners. A restore to an earlier point follows a runbook: stop all sends, ask the downstream about every payment touched since the restore point minus a margin, then resume. Downstream keys come from the partner's own ids, so a resend after a restore carries the same key and the downstream deduplicates it.
- Queueing and graceful degradation, not burst scaling.
- Limits are the database primary and downstream capacity, not compute. Partition by the payment's creation month from day one (see [Retention and erasure](#retention-and-erasure)); buy disk until restore time or procurement says otherwise.
- Sharding is far off. If ever: partner as the tenant key, never the downstream (unknown at ingestion, uneven, and partner queries would scatter).

## Retention and erasure

Payment records are financial records, so the legal obligation to keep them protects them from erasure requests, but only for the data the obligation covers and only for as long as it lasts.

- **Keep for the legal period.** Payment requests, events, received files and raw provider messages are kept for the longest period that applies to them: anti-money-laundering record keeping (five years in the EU) and national bookkeeping law (often six to ten years). Compliance sets the periods per data class.
- **During the period, an erasure request does not delete them.** The GDPR right to erasure does not apply where processing is needed to meet a legal obligation (Article 17(3)(b)). The request is recorded and answered, and the data is restricted to those obligations: no other use, such as analytics or marketing.
- **Data the obligation does not cover gets short retention.** Technical logs, delivery attempts and redundant copies are deleted on their own schedule, or on request.
- **After the period, delete.** Every table carries the payment's creation month and is partitioned by it, and a month is dropped only when all its payments are final and past the period, so a payment's history is never split. The received files are deleted with it. The deduplication key lives in its own small table and keeps only (partner, end-to-end id) after the period; the content hash, which a known id could brute-force back to debtor data, goes.
- **Partition upkeep cannot stall the engine.** Partitions are created at least three months ahead, with a lock timeout and retries. The alert fires when fewer future partitions exist than that, and there is no default partition, so an old month can be detached without blocking writes.
- **Personal data stays out of logs and copies.** A database error can carry a whole row: the database logs terse errors, the driver does not copy server detail into exceptions, and database logs, backups and WAL archives are treated as personal data with retention no longer than the legal period. A restriction flag marks a record under an erasure request.
- The engine processes debtor data for partners, so which party is controller for which data, and who answers a debtor's request, is a decision for the DPO, as is confirming the periods. This is not legal advice.

## Controls and operations

- **Manual actions are a defined set,** written into the transition table in Step 0 as inputs, each naming its evidence: confirm an outcome with an evidence reference; resend only after the downstream confirms it has nothing; close as not paid only with statement evidence. `SETTLED` is never set by hand without a matched statement entry.
- **Maker-checker** for every manual action, with identities from the identity provider (subject ids, not names), a reason, and an extra approver above a value threshold. A bulk action freezes its list of payment ids when it is requested, and the checker approves that list. Bulk actions by cause are in the first release.
- **The manual queues have an owner:** a named team, its hours and a published resolution time, sized from the expected `UNKNOWN` rate.
- **The record protects itself.** The application role may update only the lifecycle, lease and downstream columns of a payment; the instruction columns are immutable. A deferred trigger refuses a state change without its matching event, an admin event must cite an approved action for the same payment, and each payment's events form a hash chain anchored daily in the security log. A daily job checks that the state matches the last event, `event_seq` the highest sequence, and that the content hash recomputes.
- **The outbox dispatcher** has a lane per kind of effect and per partner, with a per-partner circuit breaker and a maximum delivery age, after which the partner is pointed at the status API instead. A cancellation forward is a downstream call, so the worker makes it under the payment's lease when it finds the outbox row; the dispatcher only delivers to partners.
- Infrastructure as code and GitOps; pull request approval is maker-checker for platform changes; signed artifacts and SBOM; logged break-glass; keys in KMS/HSM.
- Containers on the bank's platform, cloud or self-hosted; portability is the exit strategy.
- Structured logs with correlation ids and an allowlist of loggable fields (no payment data). Business audit is `payment_event`; the security audit log is separate and tamper-evident.
- Threats and their controls are in [threat-model.md](threat-model.md).
- Alerts only when someone has something to do, each with an owner and runbook: oldest non-terminal item per state, unknown queue age, ingestion stalled, per-partner rejection spike, downstream circuit open, webhook delivery failing, reconciliation mismatch, unmatched statement credits, quarantined payments, a scheduled job that missed its run, too few future partitions, outbox delivery lag (read from the table, never through the outbox). Business rejections stay out of technical failure ratios. Runtime per-partner pause.
- Public documentation where the status vocabulary is a contract ("accepted" means accepted for processing; cancellation is best effort; rounding rules). Legal reviews the wording. Published status values are forever.

## Delivery

- Step 0, blocking: canonical model, transition table, event schema, status vocabulary, API and file contracts.
- Parallel streams: ingestion, core lifecycle, first downstream with simulator, partner-facing status and docs, controls, platform, risk, reconciliation.
- Shared definition of done: failure scenarios tested, audit events emitted, alerts with runbook, docs, security review, deployed through the pipeline.
- Tests: developers own coverage. Non-negotiable scenarios: illegal transitions, duplicates, out-of-order events, timeouts, partial failure, resubmitted files, amount invariants. Real local Postgres, partner stubs, misbehaving downstream simulators, contract tests against downstream test environments, CI asserts the test count. Two techniques worth keeping: a sequence explorer that applies every input sequence to a depth and checks invariants on the state machine, and mutation checks (break a safeguard on purpose, confirm a test fails).
- Rollout: one pilot partner and one vetted downstream, parallel run against the existing process compared per instruction with an exit criterion stated up front, per-partner routing as rollback, then batches.

## Evidence from the proof of concept

The core loop was built and exercised against a real PostgreSQL: the state machine, ingestion with deduplication, the worker with lease and fenced writes, the provider inbox, and one fake downstream that can delay, duplicate, reorder, reject, time out and go down. It shows the recommended design holds under the failures that matter, and it changed the recommendation in these places:

- **Duplicate submission, out-of-order events, timeout to `UNKNOWN`, takeover mid-send and amount invariants** all behave as specified, including with several workers racing. Each safeguard was checked by breaking it on purpose.
- **The state machine needs a context.** A bare state forgot how far a payment got after a timeout or a cancellation; the downstream position and the cancellation flag fixed that.
- **Takeover needs a status check, and a stored reference changes the answer.** Resending after the downstream accepted a payment would create a second one; that case now goes to `UNKNOWN`.
- **Timeouts must ask before giving up,** or a lost webhook leaves a paid payment in `UNKNOWN`.
- **Events and references race.** An event arriving as its reference is stored could be left parked forever; a per-reference lock fixes it.
- **Concurrent resends of the same instructions deadlocked** until rows were inserted in a fixed order.
- **Every effect belongs in the outbox,** not only partner statuses, so that a worker dying after commit cannot lose a cancellation forward, a reconciliation request or an alert.
- **An outage must not become manual work.** With a fixed number of retries, every payment in a 30-minute downstream outage ended in `UNKNOWN`. Backoff up to a horizon resolved all of them without a person (see [Downstream outages](#downstream-outages)).
- **Paid is not settled.** Splitting them keeps the state graph honest: money that comes back after `PAID` is a mismatch, not a quiet change.

Independent code reviews of the PoC found each of these before it could matter. What the PoC does not cover, and the known gaps in it, are in [poc-limits.md](poc-limits.md).

## Risks and open questions

- **Response windows.** One window cannot serve both silent-downstream detection in `SENDING` and a debtor's decision time in `PRESENTED`. Set them per state from the downstream's real behaviour.
- **Too-late cancellation** needs a partner-visible status, which is a contract decision.
- **Source timestamps and raw provider messages** are needed for audit and must be kept from day one.
- **Claim query performance** needs a rewrite that finds due rows and rows with inputs separately before any load test.
- **Downstream key semantics** decide whether a resend is safe: key retention, what a repeated key returns, and its format. Get them from the first downstream's owners and test them before building its adapter.
- **Volumes and latency targets** are not in the brief. One primary is enough at the volumes the brief implies, but that needs a load test with a synchronous standby before go-live.
- **Reconciliation and manual resolution** of `UNKNOWN` and `MISMATCH` are where operations will live day to day; design them with operations early. [recovery.md](recovery.md) lists every cause.
- **Downstreams without a status endpoint** recover through key-based resends, event replay, reports and statements, and more of their failures need a person ([recovery.md](recovery.md#without-a-status-endpoint-designed)).
- **Front doors, validation rules and screening** are scoped by the questions at the top, and need partners and the bank's risk functions.
