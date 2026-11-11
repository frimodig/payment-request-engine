# payment-request-engine

Architecture proposal and proof-of-concept skeleton for an engine that receives payment requests from business partners (ISO 20022 pain.013 style, Request to Pay), tracks each request through its lifecycle, routes it to downstream payment systems, and reports status back.

- Phase 1: file / batch submission.
- Phase 2: near-real-time API, one request at a time.

## Contents

- [docs/architecture.md](docs/architecture.md): the proposal, open questions, decisions and trade-offs.
- [docs/database.md](docs/database.md): tables, keys and the rules they enforce.
- [docs/architecture.png](docs/architecture.png): one-page diagram.
- `src/main/resources/db/migration`: the schema as a Flyway migration.
- `src/main/kotlin`: skeleton only.

## Status

Design proposal plus a bare skeleton. The PoC is not implemented yet; see [CLAUDE.md](CLAUDE.md) for the intended scope.

## Build

Requires JDK 21 and Docker (for Testcontainers). Generate the Gradle wrapper once with a local Gradle install:

```
gradle wrapper
./gradlew build
```
