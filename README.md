# payment-request-engine

Architecture proposal and proof-of-concept skeleton for an engine that receives payment requests from business partners (ISO 20022 pain.013 style, Request to Pay), tracks each request through its lifecycle, routes it to downstream payment systems, and reports status back.

- Phase 1: file / batch submission.
- Phase 2: near-real-time API, one request at a time.

## Contents

- [docs/architecture.md](docs/architecture.md): the proposal as the PoC refined it, open questions, decisions and trade-offs.
- [docs/database.md](docs/database.md): the database structure: tables, columns, indexes and the rules they enforce.
- [docs/poc-limits.md](docs/poc-limits.md): what the PoC leaves out or gets wrong.
- [docs/architecture.png](docs/architecture.png): one-page diagram.
- `src/main/resources/db/migration`: the schema as a Flyway migration.
- `src/main/kotlin`: the PoC core loop.

## Status

Design proposal plus a working PoC of the core loop, no HTTP:

- `State.kt`, `StateMachine.kt`: the pure state machine. Transitions return valid or invalid, side effects as data.
- `Ingestion.kt`: submissions become deduplicated instructions (partner + end-to-end id).
- `Store.kt`, `Worker.kt`: claim with `FOR UPDATE SKIP LOCKED`, lease, fenced write, one transaction per state change.
- `Downstream.kt`, `FakeDownstream.kt`: the downstream boundary and a fake that can delay, time out, reject, duplicate and reorder.
- `src/test`: unit tests, and scenario tests against a real Postgres (`ScenarioTests`).

Known PoC gaps are listed in [docs/poc-limits.md](docs/poc-limits.md).

## Build

Requires JDK 21 and Docker (for Testcontainers).

```
./gradlew build
```

On macOS with Docker Desktop, if Testcontainers fails to start its Ryuk container with a
`docker.sock` mount error, set `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`.
