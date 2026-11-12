# Decision log

Decisions taken while building the proof of concept, with the reason for each, in the order they were made. Items 1 to 13 were made in an unattended overnight run and reviewed afterwards; later items follow the code reviews and the choices made on them. The design as it stands is in [architecture.md](architecture.md); open gaps are in [poc-limits.md](poc-limits.md); the review findings and what became of them are in [review-findings.md](review-findings.md).

To run the checks you need JDK 21 and Docker; see the README.

1. State machine takes and returns a `Context(state, position, cancelRequested)`. `position` is the last known downstream state (SENDING/PRESENTED/ACCEPTED), kept across UNKNOWN and CANCELLATION_REQUESTED. Needs migration V2 (two columns on payment_request). Why: checker round 2 showed forgetting position gave wrong results after a timeout.
2. All effects are persisted as `outbox` rows in the same transaction (kind = effect type), which resolves the doc conflict and the "lost after commit" gap. Dispatching: only PartnerStatus is a partner delivery; others are internal kinds. No dispatcher is built in the PoC.
3. Partner-visible states: PRESENTED, ACCEPTED, CANCELLATION_REQUESTED, PAID, REJECTED, REFUSED, EXPIRED, CANCELLED.
4. Contradicting final event on a terminal payment: STALE plus RaiseAlert.
5. Cancel on UNKNOWN is forwarded (CANCELLATION_REQUESTED) unless the position is ACCEPTED (too late).
6. Money constructor validates the ISO currency, like parse.
7. Worker design: one claim = pending provider events, then pending cancellations, then at most one timed step; each fenced write renews the lease; lease released only after success (a failure leaves it to expire, like a crash).
8. Every effect, including internal ones (reconciliation, alerts, forward cancellation), is an `outbox` row written in the same transaction (docs/database.md updated). Nothing dispatches them.
9. After a send fails or times out the worker asks the downstream for status by idempotency key: Known -> record the reference and keep waiting; otherwise UNKNOWN + reconciliation row. Silence after a known reference -> UNKNOWN. NotFound on takeover -> resend under the SAME key (key = payment id + attempt).
10. A step that leaves the state unchanged (stale event, position update) never moves the due time, so repeated events cannot postpone a timeout.
11. Provider events are matched by downstream reference only; events for a payment that timed out before its reference was known stay PARKED (poc-limits.md).
12. Fake downstream lives in src/main (can back a demo); tests mutated: removing the lease-token fence, using a random idempotency key and ignoring leases were each caught (the fence one needed an extra test, added).
13. Used a checker agent instead of /code-review for slices 2-4: the files are untracked, so there is no git diff for the skill to review.

14. Checker round on slices 2-4 found: takeover via a cancellation input could lose the downstream reference (fixed: learn the reference by status query before acting on an input, and before timing out CANCELLATION_REQUESTED/PRESENTED/ACCEPTED); inbox/relink race (fixed: per-reference advisory lock in receive and in the reference-storing transaction); concurrent resends deadlocked (fixed: insert in sorted order, results in caller order); unescaped JSON from downstream reason codes (fixed: jsonString); missing event_seq fence test (added). Each fix has a test and each test was shown to fail when its fix is removed.
15. Not done: /code-review (files untracked, no diff) and a re-check agent on the final fixes. Recommend running /code-review high after reviewing and staging.

16. After the first /code-review (high), the user took all proposed fixes: reason codes are cleaned and bounded; the claim order uses COALESCE(next_action_at, created_at) so inputs on waiting payments are not starved; an unavailable status query is retried (3 times, 30 s apart) before UNKNOWN; reference bookkeeping events are TECHNICAL; scheduleFor derives from State.hasAutomaticWork/waitsForDownstream; CLAUDE.md heading, Main.kt text and poc-limits updated. Pool and test-seam findings are recorded as limits only.

17. Second /code-review (high, main..HEAD): fixed input-first claim order (inputs before any timer, whatever the due time), per-outage retry counting, rollback no longer hides the cause, length-prefixed content hash, wrapper checksum pinned (value from services.gradle.org, matched against the downloaded zip), `/bin/` ignore. Due-time reset on input-only learnReference: fixed without a behaviour test, since every reachable input also changes the state and the due time with it. Cancellation refusal stays a documented limit (needs a new partner status).

18. Final /code-review max, then the user chose: fix the first five findings, document the rest, work straight on main, update the architecture from the PoC, document the DB structure. Done: status check before timeout (alive keeps waiting, silence retries then UNKNOWN), overdue SENDING checked before a cancellation, NotFound with a stored reference goes UNKNOWN instead of resending, unusable/duplicate references and throwing status calls end in UNKNOWN, CANCELLED confirmation refused once position is ACCEPTED. Pushed to origin/main as 9d89a7d and 2f67fde (92 tests green).
