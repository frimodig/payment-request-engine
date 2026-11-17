# payment-request-engine

A recommendation for how to build a partner payment processing engine: it receives payment requests from business partners (ISO 20022 pain.013 style, Request to Pay), tracks each request through its lifecycle, routes it to downstream payment systems, and reports status back. The core loop was built as a proof of concept to check the design.

- Phase 1: file / batch submission.
- Phase 2: near-real-time API, one request at a time.

## Contents

- [docs/architecture.md](docs/architecture.md): the recommendation as one argument: key decisions, how a payment flows, the states and partner statuses, the evidence from the proof of concept, the risks and the first-release cut.
- [docs/database.md](docs/database.md): the recommended data model: tables, columns, indexes and the rules they enforce, plus the claim, availability, partitioning, retention and integrity.
- [docs/recovery.md](docs/recovery.md): how the engine recovers from failures, with and without a downstream status endpoint, and what still needs a person.
- [docs/operations.md](docs/operations.md): how the engine is run: gates, manual actions and their queues, alerts, deploys, scheduled jobs and the platform.
- [docs/poc-limits.md](docs/poc-limits.md): what the proof of concept does not cover, and its known gaps.
- [docs/review-findings.md](docs/review-findings.md): what the code reviews and the three reviews of the plan found, and what became of each finding.
- [docs/threat-model.md](docs/threat-model.md): threats per trust boundary and the control for each.
- [docs/poc-decisions.md](docs/poc-decisions.md): the decision log, with the reason for each choice.
- [docs/study-guide.md](docs/study-guide.md): a guide to understanding and defending the recommendation: ideas, code reading order, exercises and practice questions.
- [docs/architecture.png](docs/architecture.png): one-page diagram. Its source is [docs/architecture.svg](docs/architecture.svg); after editing it, re-render the PNG (on macOS: `qlmanage -t -s 2142` on a square copy, then crop with `sips`).
- `src/main/resources/db/migration`: the schema as Flyway migrations.
- `src/main/kotlin`: the proof-of-concept core loop, no HTTP:
  - `State.kt`, `StateMachine.kt`: the pure state machine. Transitions return valid or invalid, side effects as data.
  - `Ingestion.kt`: submissions become deduplicated instructions (partner + end-to-end id).
  - `Store.kt`, `Worker.kt`: claim with `FOR UPDATE SKIP LOCKED`, lease, fenced write, one transaction per state change.
  - `Downstream.kt`, `FakeDownstream.kt`: the downstream boundary and a fake that can delay, time out, reject, duplicate, reorder and go down.
  - `WebhookTarget.kt`: decides whether a partner's webhook URL may be called (no internal addresses).
- `src/test`: unit tests, and scenario tests against a real Postgres.

## How it was built

I designed the recommendation and built the proof of concept with Claude Code, using my own agent workflow: agents implement small slices, separate reviewer agents check each one from a narrow angle, and every finding ends in a fix with a test or a documented limit. `/code-review` in the logs is Claude Code's review command. The decisions are mine; [docs/poc-decisions.md](docs/poc-decisions.md) records each one and why.

## Build

Requires JDK 21 and Docker (for Testcontainers).

```
./gradlew build
```

On macOS with Docker Desktop, if Testcontainers fails to start its Ryuk container with a
`docker.sock` mount error, set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
