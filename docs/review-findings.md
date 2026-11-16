# Review findings

The proof of concept was reviewed four times (three rounds at high effort, then a final round at max effort with ten finder angles and independent verifiers). This page records what was found and what became of each finding. Everything marked "fixed" has a test that fails without the fix. Everything marked "documented" is listed in [poc-limits.md](poc-limits.md).

## Adversarial review of the plan

After the code reviews, two independent challengers reviewed the recommendation itself. One asked whether it is the right thing to build; the other asked whether the proof of concept proves its technical claims. The brief is deliberately general, so findings that asked to pin down the product, scheme or downstreams were answered by keeping the design general rather than by narrowing it.

| Finding | Outcome |
|---|---|
| Every payment caught in a downstream outage of about 90 seconds or more went to `UNKNOWN` for a person | Fixed: backoff up to an outage horizon ([architecture.md](architecture.md#downstream-outages)); tested with a 30-minute outage |
| A send that failed while the downstream answered "not found" went straight to `UNKNOWN` | Fixed: resent later under the same key |
| (Re-check of the fixes) A status answer that reported nothing new did not end an outage, so a short outage hours after another sent payments straight to `UNKNOWN` | Fixed: a status answer that finds the payment ends an outage |
| `PAID` was final, so money coming back after it had no place, and partial payment was undefined | Fixed: `PAID` waits for `SETTLED`, which reconciliation confirms for the full amount; a different outcome after `PAID` is a `MISMATCH` |
| The docs said files are ingested in bounded transactions; the PoC uses one transaction per submission | Corrected: staged, chunked ingestion is the recommendation, and the gap is listed |
| Resend safety rests on downstream key semantics nobody has checked: retention, what a repeated key returns, format (our key is 38 characters, ISO ids 35) | Documented as adapter requirements and a risk |
| "The four downstream styles differ only inside the adapter" was untested | Reworded: only the synchronous style was built |
| No timeouts on calls, leases on each instance's clock | Documented; the recommendation now requires both |
| One large file delays API traffic | Documented; the recommendation adds a claim lane for interactive work |
| Sanctions screening could be set to fail open | Changed: always fails closed |
| Keeping files as the audit record contradicted deleting them on erasure | Resolved: kept for the legal period, erasure requests restrict use ([architecture.md](architecture.md#retention-and-erasure)) |
| No threat model | Added: [threat-model.md](threat-model.md) |
| End-to-end ids reused by a partner are rejected forever | Kept as a published rule: every instruction needs a new id, and the key outlives archiving |
| The claim-query figures cannot be reproduced from the repository | Labelled as a one-off measurement |
| Product, scheme, build-or-buy, volumes, ownership and the Postgres-versus-workflow-engine choice | Left open on purpose for the exercise; volumes and downstream key semantics are listed as risks |

## Second adversarial review of the plan

Three independent challengers attacked the plan for gaps: operations and failure, the payment domain and partner contract, and security, data and scale. Two of them tested their claims on a throwaway local PostgreSQL. The user decided the eight open questions; every gap is now answered in the plan as a design, not in the proof of concept.

| Gap | Outcome |
|---|---|
| One synchronous standby: losing it stops every write; a cancelled replication wait reports success; no RPO, RTO or restore plan | Two standbys with quorum commit, client-side commit timeout as "outcome unknown", targets and restore runbook ([architecture.md](architecture.md#data-availability-and-scale)) |
| The application role can change amounts and history unseen; the event sequence is not unique | Column grants, immutable instruction columns, unique sequence, hash chain anchored in the security log, daily consistency job |
| Partial or several credits are called normal but sent to `MISMATCH` | Running total; `SETTLED` when the total meets the request's amount rule |
| Reconciliation has no workable matching or ownership | Statements a condition of onboarding; matching order; one credit per request; unmatched credits to the partner and an owner |
| `ACCEPTED` never ends when the debtor does not pay; expiry has no owner; late money cannot correct a final state | `NOT_PAID` at a business deadline; expiry checked before each send and confirmed downstream; `PAID_LATE` |
| The partner sees a reassuring status while a person investigates | Under-review flag with a date, and a published resolution time |
| Status events cannot be ordered, fetched since a point, or trusted | Event sequence in every status, a "changes since" feed, a daily report, signed webhooks |
| File partners cannot cancel; the pain.014 mapping is undefined | File cancellation per instruction and per submission; mapping in Step 0 |
| Manual actions are undefined and bulk actions unbounded | A defined set with evidence, frozen bulk lists, identity-provider ids, value thresholds, a named owning team |
| Poison payments retry forever | Failure count, backoff, `QUARANTINED` |
| No way to stop sends to a downstream or to all | Per-downstream pause with the horizon frozen in maintenance windows; a global kill switch |
| Fairness and backpressure are not expressible in the claim | Claim picks lane and partner first; "in flight" means leased now; 429 and delayed acknowledgements past a drain time |
| No rule for deploying while payments are in flight | Expand, then contract; transition-table version on each event |
| The outbox dispatcher has no ordering, lanes or maximum age, and the docs disagree on who forwards a cancellation | Lanes per effect and partner, maximum delivery age; the worker makes downstream calls |
| Retention by partition drop splits a payment's history; partition upkeep can block writes | Partition by creation month; drop only when every payment is final; partitions three months ahead, no default partition |
| Debtor data in database logs, backups and a forever-kept hash | Terse errors, logs and backups as personal data, hash dropped after the period |
| A database restore breaks downstream deduplication | The downstream key derives from the partner's own ids |
| A re-exported file with fresh ids is processed twice; a reused message id merges files | Possible-duplicate hold by file hash or totals; same message id with a different hash is a conflict |
| Screening hits have no review state; reasons can tip off | `SCREENING_REVIEW` with a two-person decision; one generic compliance reason code |
| XML parsing is not hardened | No DTDs or external entities, pinned schema, streaming limits |
| NOTIFY inside state-change transactions makes commits wait for each other under synchronous replication | Wake-ups from a separate session, outside those transactions |
| No rule for re-routing a payment or retiring a downstream | Bound at first send; drain before retirement |
| A multi-currency control sum does not fit minor units; the currency exponent comes from the JDK | Control sum as an exact decimal; exponent stored with each amount |

The review also corrected the platform slide's reason against Kafka: a persistent topic could be the only store, with no dual write, but the status API, due times and reconciliation need a queryable database anyway.

## Final review (max effort)

| # | Finding | Outcome |
|---|---|---|
| 1 | A takeover resent a payment whose downstream reference was already stored; the second reference replaced the first | Fixed: the payment goes to `UNKNOWN` |
| 2 | A downstream reference the database cannot store (NUL, oversize, duplicate) left the payment retrying forever in `SENDING` | Fixed: goes to `UNKNOWN`. The inbox still throws for an event whose reference holds a NUL character (documented) |
| 3 | An overdue takeover applied a pending cancellation before checking the downstream, losing the reference | Fixed: the overdue send is checked first |
| 4 | `PRESENTED`, `ACCEPTED` and `CANCELLATION_REQUESTED` timed out to `UNKNOWN` without asking the downstream, so a lost webhook left a paid payment unknown | Fixed: the downstream is asked first |
| 5 | A `CANCELLED` confirmation after the debtor accepted was applied once the payment had timed out to `UNKNOWN` | Fixed: a mismatch, with or without the timeout |
| 6 | One response window serves `SENDING`, `PRESENTED` and `ACCEPTED` | Documented; set per state from real downstream behaviour |
| 7 | A row the code cannot map (unknown state, currency) blocks every claim | Documented; constrain `state` in the schema |
| 8 | A contradicting final event (`PAID` after `CANCELLED`) is a technical event | Documented |
| 9 | Event timestamps are the apply time, and the inbox keeps only the status, not the raw message | Documented |
| 10 | A `status()` call that throws stopped the payment ever reaching `UNKNOWN` | Fixed: treated as unavailable |
| 11 | A NUL or very long partner string aborts the whole submission | Documented |
| 12 | `Money.parse` has no length limit | Documented |
| 13 | Within inputs, claims are ordered by due time with nulls last, not by arrival | Documented |
| 14 | The partner-visible status order during a cancellation depends on delivery order | Documented |
| 15 | No deterministic test covers the live-lease predicate in `Store.claim` | Documented |

Lower-severity findings from the same review, all documented in [poc-limits.md](poc-limits.md) or judged trivial: the send catch block swallows an interrupt, cancellations are audited with a constant actor id, a reason code cut at 64 characters can split a surrogate pair, `FakeDownstream` does not record a scripted reject, one test relies on a short sleep, and several doc lines had drifted from the code (corrected).

Measured and not fixed: the claim query scans `payment_request` on every poll (about 250 to 300 ms per claim at 50k rows, against about 0.5 ms for a rewrite; the rewrite is in [poc-limits.md](poc-limits.md)), and about 20 small transactions run per payment before the send.

## Earlier rounds

- **Checker rounds on the state machine** found that the machine forgot how far a payment got after a timeout or a cancellation. The fix was the context (state, downstream position, cancellation requested) and migration V2.
- **Checker round on the worker** found five defects, all fixed with tests: a takeover through a cancellation lost the reference, an event arriving while the reference was being stored could stay parked forever, concurrent resends of the same instructions deadlocked, unescaped JSON from a downstream reason code, and a missing test for the event-sequence fence.
- **First `/code-review` (high)** led to: reason codes cleaned and bounded, input-first claim ordering, a bounded retry for an unavailable status query, reference bookkeeping recorded as technical, and the schedule derived from the state's own flags.
- **Second `/code-review` (high)** led to: per-outage retry counting, a rollback failure no longer hiding its cause, a length-prefixed content hash, a pinned Gradle checksum and a scoped `.gitignore`.
- **Third `/code-review` (high, on the fixes)** found eight items; two were fixed (catching `Throwable` around rollback and a stale doc line) and the rest were documented as unreachable in normal use or PoC-scale.
