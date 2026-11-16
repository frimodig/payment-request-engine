# payment-request-engine

A recommendation for how to build a partner payment processing engine: it receives payment requests from business partners (ISO 20022 pain.013 style, Request to Pay), tracks each request through its lifecycle, routes it to downstream payment systems, and reports status back. The core loop was built as a proof of concept to check the design.

- Phase 1: file / batch submission.
- Phase 2: near-real-time API, one request at a time.

## Contents

- [docs/architecture.md](docs/architecture.md): the recommendation, structured around the brief, with the evidence from the proof of concept and the open risks.
- [docs/database.md](docs/database.md): the recommended data model: tables, columns, indexes and the rules they enforce.
- [docs/poc-limits.md](docs/poc-limits.md): what the proof of concept does not cover, and its known gaps.
- [docs/review-findings.md](docs/review-findings.md): what the code reviews and the adversarial review of the plan found, and what became of each finding.
- [docs/threat-model.md](docs/threat-model.md): threats per trust boundary and the control for each.
- [docs/poc-decisions.md](docs/poc-decisions.md): the decision log, with the reason for each choice.
- [docs/study-guide.md](docs/study-guide.md): a guide to understanding and defending the recommendation: ideas, code reading order, exercises and practice questions.
- [docs/architecture.png](docs/architecture.png): one-page diagram.
- `src/main/resources/db/migration`: the schema as Flyway migrations.
- `src/main/kotlin`: the proof-of-concept core loop, no HTTP:
  - `State.kt`, `StateMachine.kt`: the pure state machine. Transitions return valid or invalid, side effects as data.
  - `Ingestion.kt`: submissions become deduplicated instructions (partner + end-to-end id).
  - `Store.kt`, `Worker.kt`: claim with `FOR UPDATE SKIP LOCKED`, lease, fenced write, one transaction per state change.
  - `Downstream.kt`, `FakeDownstream.kt`: the downstream boundary and a fake that can delay, time out, reject, duplicate and reorder.
- `src/test`: unit tests, and scenario tests against a real Postgres.

## Build

Requires JDK 21 and Docker (for Testcontainers).

```
./gradlew build
```

On macOS with Docker Desktop, if Testcontainers fails to start its Ryuk container with a
`docker.sock` mount error, set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
