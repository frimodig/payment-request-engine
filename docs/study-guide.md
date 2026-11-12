# Study guide

For the person who has to explain and defend this recommendation to peers. It tells you what to understand, in what order, where each idea lives in the code, and which test proves it. About a day to work through, half a day if you skip the exercises.

Read in this order: [the problem](#1-the-problem-in-one-page), [the domain](#2-domain-primer), [the ideas](#3-the-ideas-that-carry-the-design), [the code](#4-reading-the-code), [exercises](#5-exercises), then [practice questions](#6-practice-questions). The [glossary](#7-glossary) is for lookup.

## 1. The problem in one page

Partners send payment requests (pain.013-style). Phase 1 is files with thousands of instructions; Phase 2 is an API, one request at a time. The engine must receive, validate, transform, apply rules, route to downstream payment systems of any style, track every instruction's lifecycle, and tell the partner what happened.

Everything hard about this comes from one fact: **the downstream systems are slow, unreliable and out of our control, and money is involved.** A call can time out after the downstream already did the work. Events arrive twice, late, or in the wrong order. A worker can die halfway. The design is a set of answers to those failures, in this order of importance:

1. Never pay twice and never lose a payment: idempotency keys, one owner per payment at a time, writes that cannot overwrite a newer state.
2. Never guess: when the outcome is unknown, say so (`UNKNOWN`), keep the evidence, and hand it to a person or to reconciliation. A timeout is never a failure.
3. Be able to explain every status: an append-only event history, every effect recorded in the same transaction as the state change.
4. Stay simple: one service, one database, due times instead of a queue broker.

Skim [architecture.md](architecture.md) first (the brief table at the top shows where each requirement is answered), then return here.

## 2. Domain primer

- **pain.013** is a Creditor Payment Activation Request: a *Request to Pay*. The creditor (our partner) asks the debtor to pay. **pain.014** is the status report that goes back. An accepted request is followed by a separate credit transfer, possibly several or a partial one. So the final status waits on a person (the debtor) and arrives asynchronously, in both phases. Phase 2 removes batching, not asynchrony.
- **End-to-end id.** The partner's id for one instruction. With the partner it forms the idempotency key at the door: the same pair is the same instruction.
- **camt.053 / camt.054** are account statements and notifications. They are how you check that what the engine believes happened actually happened (reconciliation).
- **Downstream styles.** Synchronous API, asynchronous with webhooks, file-based, message-based. They differ only inside the adapter; see the table in [architecture.md](architecture.md#downstream-systems).
- **Amounts** are integers in minor units plus an ISO 4217 currency, because floats cannot represent money exactly and because currencies have different exponents (EUR has two decimals, JPY none).
- **Published status values are forever.** Partners build on them, so only states that mean something to a partner are exposed.

If a peer asks "what is the difference between *accepted* and *paid*": accepted means the debtor agreed (or the request was accepted for processing); paid means the credit transfer happened. They are different states and different messages.

## 3. The ideas that carry the design

Each idea: what it is, why it exists, where it lives, and the test that shows it working. Test names are the titles in [ScenarioTests.kt](../src/test/kotlin/engine/ScenarioTests.kt), [HardeningTests.kt](../src/test/kotlin/engine/HardeningTests.kt) and [StateMachineTest.kt](../src/test/kotlin/engine/StateMachineTest.kt).

### 3.1 Every state with work has a due time

Each payment row has `state` and `next_action_at`. There is one kind of work: claim a payment that is due or has a new input, apply the input or do the step the state calls for, write the new state and the next due time. No due time means no automatic work: terminal, or waiting on a person.

- *Why:* retries, backoff, timeouts and expiry are all "a due time". There is no separate scheduler, no queue broker, and a crashed worker loses nothing because the work is in the table.
- *Where:* `Worker.runOnce`, `Worker.scheduleFor`, `State.hasAutomaticWork`.
- *Proof:* the "no due time" rule is checked for every state in `StateMachineTest` ("only non-terminal non-waiting states have automatic work").

### 3.2 Claim, lease, fence

A worker claims one payment with `UPDATE ... WHERE id = (SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1)`. That sets a lease and increments `lease_token`. Work happens outside any transaction. The write that records the result is a compare-and-set on the lease token **and** the event sequence number. If either moved, nothing is written.

- *Why:* two workers must never both act on a payment, and a slow worker that wakes up after a takeover must not overwrite what the new holder did. `SKIP LOCKED` lets workers share the table without waiting on each other.
- *Where:* `Store.claim`, `Store.apply`, `Store.release`.
- *Proof:* "a stalled worker that wakes up after a takeover cannot write", "claiming alone fences the previous holder even if the new holder has not written yet", "a write based on a stale read is fenced by the event sequence", "several workers never double-process a payment and leave a gap-free history".
- *Detail to remember:* a worker that fails does not release its lease. It expires like a crash. That is deliberate: it allows takeover and stops a broken payment being retried in a hot loop.

### 3.3 One transaction per state change, with an outbox

A state change writes the payment row, one `payment_event` row per step, one `outbox` row per effect, and marks the consumed input as applied, all in one transaction.

- *Why:* if effects were sent after the commit, a worker dying in between would lose them. If sent before, a rollback would have announced something that did not happen. The outbox makes the effect durable with the state, and a dispatcher delivers it afterwards, retrying as needed.
- *Where:* `Store.apply`, `Store.insertOutbox`, table `outbox` in [database.md](database.md#outbox).
- *Not built:* the dispatcher. Only the writing side exists.

### 3.4 The state machine is pure

`PaymentStateMachine.transition(context, input)` returns a new context, the steps taken, and the effects, as data. It does no I/O. The context carries the state, the last known downstream position, and whether a cancellation was requested.

- *Why:* a pure table is testable exhaustively. You do not need a database to check that no sequence of events moves a payment backwards or pays a cancelled payment.
- *Where:* [StateMachine.kt](../src/main/kotlin/engine/StateMachine.kt), [State.kt](../src/main/kotlin/engine/State.kt).
- *Proof:* the sequence explorer in `StateMachineTest` ("every reachable transition is well-formed" and the property tests after it) applies every sequence of inputs up to a depth and checks invariants on each.
- *The lesson of the PoC:* a bare state was not enough. After a timeout a payment is `UNKNOWN`, and without the *position* (how far the downstream got) a late event cannot be judged. That is why the context exists and why V2 adds two columns.

### 3.5 Out-of-order events are resolved by the graph, not by clocks

A provider event names a target state. The machine compares it with the payment's position:

| Target is | Outcome |
|---|---|
| Ahead and reachable | `APPLIED`; skipped states are `INFERRED` and their effects still emitted |
| Behind or equal, or the payment is terminal | `STALE`: recorded, not applied |
| Not reachable, or the payment was never sent | `NO_PATH`: the payment goes to `MISMATCH` and an alert is raised |

- *Why:* timestamps from other systems lie (clock skew, retries). The state graph is the only thing both sides agree on.
- *Proof:* "out-of-order and duplicated provider events end in PAID with each partner status once", "an event that contradicts the state parks the payment as MISMATCH with an alert".

### 3.6 Idempotency key and the takeover rules

The key sent to the downstream is `paymentId:sendAttempt`. It is derived from the payment, never from the worker, so a different worker that takes over sends the same key and the downstream deduplicates.

`SENDING` is recorded **before** the call. So a worker that finds an overdue `SENDING` payment knows a call may have happened, and checks the downstream instead of resending:

| The downstream says | We hold a reference? | Result |
|---|---|---|
| Knows it | any | Store the reference; apply any reported state |
| Does not know it | no | It never arrived: resend under the same key |
| Does not know it | yes | It accepted it once and lost it: `UNKNOWN`, never a resend |
| No answer, or its adapter fails | any | Retry the query a few times, then `UNKNOWN` |

- *Where:* `Worker.checkSending`, `Worker.statusUnavailable`, `Worker.sendAndRecord`.
- *Proof:* "a worker that dies after recording SENDING is taken over and resends under the same key", "a worker that died after the downstream accepted is taken over without a second send", "no blind resend when the downstream has lost a payment it once accepted".

### 3.7 Unknown never expires into failed

A timeout moves a payment to `UNKNOWN`, never to a failure state, and `UNKNOWN` has no due time: it waits for a person, a reconciliation, or a late event that resolves it. Before giving up, the worker asks the downstream (`Worker.checkWaiting`): if it moved on, the payment is caught up; if it still reports the same status, the payment keeps waiting; only silence leads to `UNKNOWN`.

- *Why:* a failed payment may be retried or reported as failed to the partner. If it actually went through, that is a double payment or a lie.
- *Proof:* "a timeout nobody can explain is UNKNOWN, queued for reconciliation, and never turns into failed", "when the response window passes the downstream is asked, and a lost PAID webhook is recovered".

### 3.8 The provider inbox: store first, apply later

Downstream events are stored exactly as received and acknowledged immediately, deduplicated on (provider, event id). They are applied later by the worker that holds the payment's lease. An event for a reference we do not know yet is `PARKED` and linked when the reference is stored.

- *Why:* the downstream must not wait on our processing, duplicates must not reach the state machine, and an event must never be dropped because it arrived early.
- *The subtle part:* an event arriving at the same moment the reference is stored could be inserted parked just as the reference commits, and nobody links the two. A per-reference Postgres advisory lock in `ProviderInbox.receive` and in the reference-storing transaction closes it.
- *Proof:* "an event that arrives before the reference is stored is parked, then applied", "an event received while the reference is being stored is never left parked".

### 3.9 Ingestion and money

`Ingestion.submit` deduplicates on (partner, end-to-end id). Identical content is a no-op; the same id with different content is a **conflict**, never a silent edit. `Money.parse` accepts plain decimals only, never rounds, and rejects extra decimals, overflow and unknown currencies.

- *Proof:* "duplicate submission is a no-op and a changed resend is a conflict", "simultaneous duplicate submissions create exactly one payment", "amounts that are not exact positive minor units are rejected at the door", "totals per currency are conserved from ingestion to the downstream".

## 4. Reading the code

About 1,300 lines of main code. Read in this order; each file is short.

1. [State.kt](../src/main/kotlin/engine/State.kt): the states and which are terminal, waiting on a person, waiting on the downstream, or visible to partners.
2. [StateMachine.kt](../src/main/kotlin/engine/StateMachine.kt): `transition`, then `providerReported` (the heart of 3.5), then `cancelRequested` and `responseTimeout`.
3. [Money.kt](../src/main/kotlin/engine/Money.kt) and [Db.kt](../src/main/kotlin/engine/Db.kt): small helpers; note `lockReference`.
4. [Store.kt](../src/main/kotlin/engine/Store.kt): `claim`, `apply`, `release`. The whole concurrency story is in these three.
5. [Ingestion.kt](../src/main/kotlin/engine/Ingestion.kt): `submit` and the content hash.
6. [Downstream.kt](../src/main/kotlin/engine/Downstream.kt) and [FakeDownstream.kt](../src/main/kotlin/engine/FakeDownstream.kt): the boundary, and the fake that can delay, time out, reject, duplicate and reorder.
7. [Worker.kt](../src/main/kotlin/engine/Worker.kt): `runOnce`, then `timedStep`, `checkSending`, `checkWaiting`.

Then the tests, in this order: `StateMachineTest` (the explorer), `ScenarioTests` (the five failure scenarios the PoC exists to prove), `HardeningTests` (races and awkward inputs found by the reviews; each failed before its fix).

## 5. Exercises

Run the tests first (JDK 21 and Docker needed; see the [README](../README.md)):

```
./gradlew build
./gradlew test --tests 'engine.ScenarioTests'
```

1. **Break the fence.** In `Store.apply`, remove `AND lease_token = ?` from the `WHERE` clause (and the parameter). Which tests fail? Which do not, and why? (Hint: the event sequence also fences. That is why "claiming alone fences the previous holder..." exists.)
2. **Resend blindly.** In `Worker.checkSending`, make `NotFound` always call `sendAndRecord`. Which test fails, and what would the downstream have done in production?
3. **Trust the clock.** Imagine the state machine decided by event timestamps instead of the graph. Write down one sequence of events that breaks it, then find the test that guards the graph version.
4. **Trace a payment.** In a scenario test, print `tables.events(id)` after the run and read the history: types, from and to states, outcomes, categories. Do it for the lost-webhook test and for a cancellation in flight. Can you explain every row?
5. **Add a status.** Add a provider status `RETURNED` to `ProviderStatus` and decide where it belongs in the graph. Which tests tell you what you forgot? (The explorer will.)
6. **Change a window.** Set the response window to 30 seconds in a scenario. What changes for a payment waiting on a slow debtor? Why does `UNKNOWN` not happen as long as the downstream keeps answering?

## 6. Practice questions

Answer aloud before opening each one.

<details><summary>Why Postgres and polling instead of Kafka or a queue?</summary>

The work is state, not messages: every payment needs a lifecycle, an audit trail, timeouts and fenced writes, and those live in a database anyway. A broker would add a second place for truth and a dual-write problem. Due times in a table give retries, backoff and timeouts for free, and `SKIP LOCKED` lets workers share it. The limit is the primary's throughput; the architecture says to measure it and split roles only when a bottleneck is measured. `LISTEN/NOTIFY` only wakes workers sooner; polling is the guarantee.
</details>

<details><summary>What stops a payment being paid twice?</summary>

Three layers. The door deduplicates on (partner, end-to-end id). Only the lease holder changes a payment, and its write is fenced on the lease token and event sequence. And the downstream receives `paymentId:attempt` as an idempotency key that is derived from the payment, never the worker, so a takeover sends the same key. On top of that, a payment the downstream has already accepted is never resent: it goes to `UNKNOWN`.
</details>

<details><summary>A call to the downstream times out. What happens?</summary>

The outcome is unknown, so the worker asks the downstream by the idempotency key. If it knows the payment, the reference is stored and the payment keeps waiting. If it does not, and we hold no reference, it never arrived and the same key is sent again. If the status call is unavailable, it is retried a few times, then the payment becomes `UNKNOWN` and a reconciliation effect is queued. It never becomes a failure.
</details>

<details><summary>What is the difference between UNKNOWN and MISMATCH?</summary>

`UNKNOWN`: we lost track of the outcome (silence). A late event can still resolve it, from the last known position. `MISMATCH`: the downstream said something that contradicts what we know (no path in the graph). It is frozen and goes to a person with an alert. Both have no due time and wait on a person.
</details>

<details><summary>Why are events resolved by the state graph and not by timestamps?</summary>

Timestamps come from other systems and can be wrong, skewed or reordered by retries. The graph is the contract both sides share: ahead and reachable is applied, behind is stale, unreachable is a mismatch. The timestamp is kept as a hint only.
</details>

<details><summary>Why do files and the API share one core?</summary>

A file is a bulk API call with the same idempotency rules. Granularity is not the difference: status, webhooks and cancellation are per instruction either way. One pipeline means Phase 2 adds a door, not a second engine, and partners on files are never forced to migrate.
</details>

<details><summary>Why one service and not microservices?</summary>

One database means a modular monolith: microservices sharing a schema give the costs of both. Every instance runs every job; roles are split only where a measured bottleneck says so. The state machine, the worker and the adapters are modules with clear boundaries, which keeps the option open.
</details>

<details><summary>What happens if the synchronous standby is unreachable?</summary>

Stop acknowledging. Zero data loss means acknowledging only after the commit is replicated. Partners resend, and the idempotency rules make that safe.
</details>

<details><summary>What does the outbox give you that sending after the commit does not?</summary>

Atomicity. The effect is stored with the state change, so a worker dying right after the commit cannot lose it, and a rollback cannot leave an announced effect behind. Delivery then retries from the table alone.
</details>

<details><summary>How do you cancel a payment that is already in flight?</summary>

Before the send it is cancelled at once and never sent. In flight it becomes `CANCELLATION_REQUESTED` and the cancellation is forwarded. The debtor may accept first; then the payment follows to `ACCEPTED` and the cancellation lost. Only a cancellation we asked for can be confirmed as `CANCELLED`, and not after acceptance. A request that arrives too late is recorded as stale; telling the partner it was refused needs a new status that is not defined yet.
</details>

<details><summary>What does the proof of concept NOT prove?</summary>

No HTTP or file front door, no outbox dispatcher, no reconciliation or manual resolution, no real downstream, no load test (the claim query scans the table on each poll and needs a rewrite before any), and a single response window for all waiting states. It proves the failure handling of the core loop, not throughput or the partner contracts. See [poc-limits.md](poc-limits.md).
</details>

<details><summary>What would you build first, and what is the biggest risk?</summary>

First the blocking step 0: canonical model, transition table, event schema, status vocabulary and the contracts, then the core lifecycle with the first downstream and its simulator. The biggest risks are operational: resolving `UNKNOWN` and `MISMATCH` and reconciliation (where operations will spend their days), and the status vocabulary, which is a contract that is hard to change once published.
</details>

## 7. Glossary

| Term | Meaning here |
|---|---|
| Fencing | Making a write fail if the writer no longer owns the payment, using the lease token and event sequence |
| Lease | A time-limited claim on a payment by one worker; renewed by each write |
| `SKIP LOCKED` | Postgres row locking that skips rows already locked, so workers do not wait on each other |
| Idempotency key | A key sent with a call so the receiver treats repeats as the same call |
| Outbox | A table of side effects written in the same transaction as the state change, delivered afterwards |
| Provider inbox | A table of downstream events stored as received, applied later by the lease holder |
| Parked | An inbox event with no matching payment yet; linked when the reference is stored |
| Position | The last known downstream state of a payment: `SENDING`, `PRESENTED` or `ACCEPTED` |
| Stale | An event recorded but not applied because it is behind or equal to what we know |
| Inferred | A state skipped by an event, recorded as passed through |
| `UNKNOWN` | We lost track of the outcome; waits on a person or a late event |
| `MISMATCH` | The downstream contradicts our state; frozen and alerted |
| Reconciliation | Comparing what we believe with account statements (camt.053/054) |
| Request to Pay | pain.013: the creditor asks the debtor to pay; the payment is a separate step |

## 8. Where to find things

| You want | Look at |
|---|---|
| The recommendation, structured around the brief | [architecture.md](architecture.md) |
| Tables, columns, indexes and the rules they enforce | [database.md](database.md) |
| What the proof of concept leaves out | [poc-limits.md](poc-limits.md) |
| Why each choice was made, in order | [poc-decisions.md](poc-decisions.md) |
| What the reviews found and what became of it | [review-findings.md](review-findings.md) |
