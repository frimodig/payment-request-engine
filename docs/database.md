# Database

PostgreSQL, single primary with a synchronous standby. The schema is in [V1__initial_schema.sql](../src/main/resources/db/migration/V1__initial_schema.sql); this page explains the rules it enforces.

## Tables

| Table | Purpose | Mutable? |
|---|---|---|
| `partner` | Partner configuration: acceptance mode, in-flight limit, runtime pause | Yes |
| `submission` | One row per file or API call, as received | Status only |
| `payment_request` | Current state of each instruction, its due time and lease | Yes |
| `payment_event` | Lifecycle history and business audit trail | Insert only |
| `provider_inbox` | Raw downstream events exactly as received | Status and payment link only |
| `outbox` | Side effects to deliver to partners (webhooks, status reports) | Delivery fields only |
| `manual_action` | Maker-checker requests for state-changing manual actions | Status, decision and applied fields only |
| `cancellation_request` | Partner cancellation requests, applied by the worker | Applied field only |

"Mutable?" is enforced by grants to the application role, which are set per environment and are not in the migration. Retention and erasure (below) run under a separate maintenance role.

## payment_request

The unit of processing. One row per instruction.

- **Identity.** `unique (partner_id, end_to_end_id)` is the idempotency key across channels and resends. `content_hash` detects a resend that reuses the id with different content: that is a conflict, never an update.
- **Money.** `amount_minor` is an integer in the currency's minor units; `currency` is the ISO 4217 code. No floats anywhere.
- **Work scheduling.** `next_action_at` is the due time. Null means no automatic work is due: either terminal (`PAID`, `REJECTED`, `REFUSED`, `EXPIRED`, `CANCELLED`) or waiting on a person (`UNKNOWN`, `MISMATCH`). Alerts on waiting states key on `state`, not on the due time. The partial index on `next_action_at` keeps the worker's claim query cheap.
- **Only the lease holder writes state.** External inputs never update this table directly: provider events go to `provider_inbox`, cancellations to `cancellation_request`, approved manual actions stay in `manual_action`. The worker picks them up under a lease (the claim query includes payments with unapplied inputs) and applies them through the state machine. This is what makes the fence complete.
- **Lease and fencing.** `lease_owner` and `lease_until` record the current claim. `lease_token` increments on every claim. The result is a compare-and-set: `WHERE id = ? AND lease_token = ? AND event_seq = ?`. A slow worker cannot overwrite a takeover, and nothing else can have written in between.
- **Event sequence.** `event_seq` is the last sequence number written to `payment_event` for this payment. The fenced update increments it by the number of events being written (more than one when a transition records inferred states) and returns the new value; every `payment_event` insert takes its `seq` from that value in the same transaction. That keeps the sequence gap-free and ordered, which the partitioned event table cannot enforce on its own.
- **Downstream.** `downstream`, `downstream_ref` and `send_attempt` are stored when the request is sent, before any response, so unsolicited events can be matched and a takeover mid-send knows which attempt was in flight. The downstream idempotency key is derived from payment id and attempt.
- **Plans.** `parent_ref` is an optional reference for whoever owns a payment plan. The engine does not interpret it.
- **State values** are not constrained in the schema while the state list is a draft.

## payment_event

Append-only. The application role has `INSERT` and `SELECT` only; no `UPDATE` or `DELETE` (granted outside the migration, per environment).

- One row per lifecycle event: `type`, `from_state`, `to_state`.
- `outcome`: `APPLIED`, `INFERRED` (a skipped state on a valid path), `STALE` (recorded, not applied) or `NO_PATH` (contradicts the current state; the payment moves to `MISMATCH`).
- `category`: `BUSINESS` or `TECHNICAL`, so retries do not bury the events an auditor needs.
- `actor_type` and `actor_id`: partner, internal service, downstream, or admin user.
- `reason` and `manual_action_id` are required for `ADMIN` events (check constraint). That the linked action is approved is enforced by the application, not the schema.
- `occurred_at` (when it happened, per the source) and `recorded_at` (when we wrote it) differ for late downstream events.
- `correlation_id` traces one file or API call across all its payments. `inbox_event_id` links to the raw provider event.
- `payload` holds mapped reason codes and downstream references, never personal data. The raw provider message stays in `provider_inbox`, linked by `inbox_event_id`.
- Partitioned by `recorded_at`, monthly. The migration creates the first months; a scheduled job creates partitions ahead of time. The default partition is a safety net that should stay empty: a row there is an alert, because it blocks creating the partition for that range. Old partitions are detached and archived, subject to the retention policy.

## provider_inbox

Raw provider events, stored and acknowledged before any processing.

- `unique (provider, provider_event_id)` drops duplicate deliveries before they reach the state machine.
- `provider_timestamp` is a hint, never the deciding vote; the state graph decides.
- `status`: `NEW`, `APPLIED`, `PARKED` (no matching payment yet). Parked events are never acknowledged-and-dropped.
- Matching: on arrival the writer extracts `downstream_ref` and resolves `payment_id` by `(downstream, downstream_ref)`, where `provider` uses the same values as `payment_request.downstream`. Matched rows are inserted `NEW`, unmatched rows `PARKED` (check constraint: parked if and only if unlinked). The send transaction that records a payment's `downstream_ref` re-links any parked rows for it and sets them `NEW`.

## outbox

Every side effect of a state change is written here in the same transaction as the state and event.

- A row belongs either to one payment or to a whole submission (a file-level status report, an all-or-nothing rejection), never both.
- Payment rows: `unique (payment_id, event_seq, kind)`, keyed on the event sequence, never on state, so a legitimate repeat of a state is not suppressed.
- Delivery uses its own due time (`next_attempt_at`) and attempt count, and is re-drivable from this table alone.

## manual_action

Maker-checker. `requested_by` and `approved_by` must differ, a decided action always records who decided and when, and only an approved action can be marked applied (check constraints). The worker applies it under a lease, through the state machine like any other input, and sets `applied_at` in the same transaction.

## cancellation_request

One per payment. The worker applies it under a lease and sets `applied_at`. The outcome (cancelled, forwarded downstream, or too late because the debtor already accepted) is a `payment_event`, not a field here.

## Personal data

Debtor and creditor identification lives in `payment_request`, not in `payment_event`. It also reaches other places, each needing its own retention and erasure rule:

- `provider_inbox.payload`: raw downstream events as received.
- `outbox.payload`: messages sent to partners.
- Received files in object storage (`submission.file_object_key`).
- Free-text `reason` fields: guidance for operators is to use references, not personal details.

Downstream calls are made by the worker directly, under its lease, not through the outbox; request bodies are not stored.

Erasure runs under the maintenance role: identification columns on `payment_request` are nulled, payloads are redacted in place (`'{}'`) once delivered, and received files are deleted. It never deletes rows that `payment_event` links to.

## Transactions

A state change is one transaction: fenced compare-and-set on `payment_request` (state, due time, `event_seq`), insert into `payment_event` with the returned sequence numbers, insert into `outbox`, and mark the consumed input (inbox event, manual action, cancellation) as applied. If the fencing check fails, nothing is written.

## Not in the schema yet

- Partner credentials and keys (KMS/HSM, not this database).
- Partner usage profiles and rule configuration.
- Reconciliation and statement matching tables.
- Per-downstream in-flight limits and the capability matrix.
- Monthly partition management (scheduled job).
- Grants enforcing the append-only and status-only rules.
