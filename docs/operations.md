# Operations

How the engine is run: the gates that stop sends, the manual actions and their queues, the alerts, deploying while payments are in flight, scheduled jobs and the platform. The design behind it is in [architecture.md](architecture.md); the states named here are defined in its [states table](architecture.md#states-and-partner-statuses).

Nothing on this page is built unless it says so. **First release** and **later** follow the cut in [architecture.md](architecture.md#delivery-and-the-first-release).

## Gates

A gate decides whether a payment may be sent now. There is one gate per downstream, one per partner and one global gate, each a row the send step reads (a few cached rows). A send goes ahead only when the global gate, its partner's gate and its downstream's gate all allow it. The downstream is known by then, because routing stores it when the payment enters `READY_TO_SEND`.

| Field | What it does | When |
|---|---|---|
| Paused | Stops sends through this gate. On the global gate this is the kill switch | First release |
| Maintenance window | A downstream's declared maintenance: sends wait and the outage horizon is frozen | Later: a second or fragile downstream |
| Circuit state, and since when | Closed, open or half-open, set by failed calls to that downstream; open raises the outage alert, half-open sends a few probes | Later: a second or fragile downstream |
| Send rate | The rate of sends through this gate, shared by retries and new sends; during warm-up it starts low and climbs to normal | Later: a second or fragile downstream |
| In-progress limit | How many of this partner's or downstream's payments may be leased before its sends wait; claims are never refused, and a request waiting days on a debtor is not leased, so it does not count | Later: a second partner |

- **A gate blocks only the send step,** first sends and resends alike. It never blocks status queries, inputs (cancellations included) or timers, so a paused partner's fraudulent instructions can still be cancelled, and a payment past its horizon still moves on. A blocked send keeps its state and is due again later, with backoff.
- **Freezes.** A pause, the kill switch or a declared maintenance window freezes the outage horizon of the payments it holds back, but never past the downstream's key retention ([recovery.md](recovery.md#downstream-outages)).
- **The in-progress limit is soft.** The send step counts the unexpired leases of that partner or downstream. It is never a counter that a release must decrement, so a worker that crashes, or fails and leaves its lease to expire, cannot leak a slot.
- **Changing a gate** is a runtime setting with maker-checker and audit.
- Warm-up after an outage, and the options it was chosen from, are in [recovery.md](recovery.md#downstream-outages).

## Manual actions

A person never writes a payment's state. Each manual action is an input: requested, approved by a second person, and applied by the worker under a lease through the state machine. Every action names its evidence. Designed, first release; the maker-checker check constraints are in the V1 schema, not exercised.

| Action | From | To | Evidence |
|---|---|---|---|
| Confirm an outcome | `UNKNOWN`, `MISMATCH` | The confirmed state, through the graph | A reference from the downstream or the statement |
| Resend | `UNKNOWN` | `SENDING` under the same key, or under a new attempt, and so a new key, only if the earlier attempt ended without payment ([attempt rule](database.md#payment_request-1)) | The downstream confirms it has nothing, or that the attempt ended |
| Close as not paid | `UNKNOWN` or `MISMATCH` | `NOT_PAID` if the debtor had accepted; `CANCELLED` before acceptance | Statement evidence that no money arrived |
| Release or reject after screening | `SCREENING_REVIEW` | `READY_TO_SEND`, or `REJECTED` with the generic compliance code | Two people from compliance |
| Retry or close a quarantined payment | `QUARANTINED` | Its previous state with the count reset; or `REJECTED` with a reason if it was never sent, else `UNKNOWN` | The cause, fixed or understood |
| Release or reject a held file | A submission `HELD` as a possible duplicate | `SPLIT` or `REJECTED`, applied by ingestion to the submission, not by the payment worker | Comparison with the earlier file, or the partner's confirmation |
| Match a credit by hand | An unmatched statement credit | A statement match for the payment, applied like any other | The statement entry |
| Cancel a payment the downstream never had | `UNKNOWN` with a cancellation pending | `CANCELLED`, or `EXPIRED` if expiry asked | The downstream confirms it never received it |

- `SETTLED` is never set by hand: it is reached only through a matched statement entry.
- **Maker-checker:** identities from the identity provider (subject ids, not names), a reason, and an extra approver above a value threshold.
- **Bulk actions by cause** are in the first release, so one approved decision can resolve many payments with the same cause. A bulk action freezes its list of payment ids when it is requested, and the checker approves that list.

## Queues and owners

Each manual queue has an owner: a named team, its hours and a published resolution time, sized from the expected `UNKNOWN` rate. While a payment waits in `UNKNOWN`, `MISMATCH` or `QUARANTINED`, the partner sees its last status with an under-review flag; `SCREENING_REVIEW` shows nothing beyond the last status. Designed, first release.

| Queue | Owner |
|---|---|
| `UNKNOWN`, `MISMATCH`, `QUARANTINED` | Payment operations |
| `SCREENING_REVIEW` | Compliance |
| Held files | Payment operations, with the partner |
| Unmatched statement credits | Reconciliation |
| Payments still open as their month nears the end of the legal period | The owner of the queue each payment is in |

Every cause that lands in a queue is listed in [recovery.md](recovery.md#what-needs-a-person).

## Alerts

An alert fires only when someone has something to do, and each has an owner and a runbook. Business rejections stay out of technical failure ratios.

| Alert | When |
|---|---|
| Oldest non-final payment per state, and the age of each manual queue | First release |
| Ingestion stalled; a per-partner rejection spike | First release |
| A downstream outage: its payments are backing off (the gate's open circuit, once built) | First release |
| A different outcome after a final state, recorded as stale: money may have moved | Built (the proof of concept writes the `ALERT` row) |
| A payment moved to `MISMATCH` or `QUARANTINED` | First release |
| Provider events parked past a deadline | First release |
| Partner delivery failing, and outbox delivery lag (read from the table, never through the outbox) | First release |
| The backlog: the age of the oldest unleased overdue payment per lane | First release |
| A scheduled job that missed its run; too few future partitions | First release |
| A month past retention with open payments | When the first month nears its legal period |
| A downstream file rejected whole; a report not arriving | With a non-synchronous downstream |

## Backpressure

When the backlog would take longer than a set time to drain, file acknowledgements say processing is delayed (first release), and the API answers `429` with `Retry-After` (when the API ships). Ingestion stays available while processing is saturated: a submission is stored before it is processed.

## Deploying while payments are in flight

Expand, then contract. A release learns to read a new state, input or status one release before anything writes it, and writing is switched on once no older instance runs. A rollback goes only to a version that reads everything already written. Each event records the transition-table version. Schema constraints are added `NOT VALID` and then validated, with a lock timeout. Designed, first release.

## Scheduled singletons

Reconciliation runs, statement matching, partition creation, retention drops and the daily consistency job need one runner at a time: ShedLock or db-scheduler recurring tasks. A missed run is an alert. Designed, first release.

## Platform

- Infrastructure as code and GitOps; pull request approval is maker-checker for platform changes; signed artifacts and an SBOM; logged break-glass; keys in KMS or an HSM.
- Containers on the bank's platform, cloud or self-hosted; portability is the exit strategy.
- Structured logs with correlation ids and an allowlist of loggable fields, with no payment data. The business audit trail is `payment_event`; the security audit log is separate and tamper-evident.
- Public documentation where the status vocabulary is a contract: "accepted" means accepted for processing, cancellation is best effort, and the rounding rules. Legal reviews the wording.
