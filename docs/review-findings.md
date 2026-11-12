# Review findings

The proof of concept was reviewed four times (three rounds at high effort, then a final round at max effort with ten finder angles and independent verifiers). This page records what was found and what became of each finding. Everything marked "fixed" has a test that fails without the fix. Everything marked "documented" is listed in [poc-limits.md](poc-limits.md).

## Final review (max effort)

| # | Finding | Outcome |
|---|---|---|
| 1 | A takeover resent a payment whose downstream reference was already stored; the second reference replaced the first | Fixed: the payment goes to `UNKNOWN` |
| 2 | A downstream reference the database cannot store (NUL, oversize, duplicate) left the payment retrying forever in `SENDING` | Fixed: goes to `UNKNOWN`. The inbox still throws for such a reference in an event (documented) |
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
