# CLAUDE.md

Read `docs/architecture.md` and `docs/database.md` before changing anything. The schema in `src/main/resources/db/migration` is the source of truth for the data model; change it through new migrations only.

## Intended PoC scope (not yet built)

Core loop only, no HTTP:

1. Explicit state machine (pure; transitions return valid/invalid with side effects as data).
2. Worker loop: claim due rows with `FOR UPDATE SKIP LOCKED`, lease + fenced write, one transaction per state change (payment_request + payment_event + outbox).
3. One fake downstream that can be told to delay, duplicate, reorder and time out.
4. Scenario tests against a real Postgres (Testcontainers): duplicate submission, out-of-order provider events, timeout to UNKNOWN, takeover mid-send, amount invariants.

## Constraints

- Kotlin, JDK 21, Gradle Kotlin DSL. Plain JDBC + Flyway unless there is a reason to add a framework.
- Amounts are `Long` minor units plus currency. No floating point.
- No credentials, personal data or real partner data in code, tests or logs.
- Dependency versions in `build.gradle.kts` were set without network access: verify and update them first.
