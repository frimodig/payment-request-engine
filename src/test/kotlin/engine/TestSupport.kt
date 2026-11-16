package engine

import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/** One disposable Postgres for the whole test run (Testcontainers removes it afterwards). */
object TestPostgres {
    private val container = PostgreSQLContainer("postgres:16-alpine").also { it.start() }
    val db: Db

    init {
        Db.migrate(container.jdbcUrl, container.username, container.password)
        db = Db.connect(container.jdbcUrl, container.username, container.password)
    }

    fun reset() {
        db.tx { conn ->
            conn.createStatement().use {
                it.execute(
                    "TRUNCATE payment_event, outbox, provider_inbox, cancellation_request, manual_action, " +
                        "payment_request, submission, partner CASCADE",
                )
            }
        }
    }
}

/** Starts inside the partition window the migration creates (2026-11 to 2027-01). */
class MutableClock(@Volatile var now: Instant = Instant.parse("2026-11-12T10:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId): Clock = this
    fun advance(by: Duration) { now = now.plus(by) }
}

data class EventRow(val seq: Int, val type: String, val from: String?, val to: String?, val outcome: String)

data class PaymentRow(
    val id: UUID, val state: String, val nextActionAt: Instant?, val eventSeq: Int, val amountMinor: Long,
    val currency: String, val downstreamRef: String?, val leaseToken: Long, val position: String?,
)

/** Reads straight from the tables, independently of the code under test. */
class Tables(private val db: Db) {
    fun payment(id: UUID): PaymentRow = db.tx { conn ->
        conn.prepareStatement(
            "SELECT id, state, next_action_at, event_seq, amount_minor, currency, downstream_ref, lease_token, downstream_position " +
                "FROM payment_request WHERE id = ?",
        ).use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "No payment $id" }
                PaymentRow(
                    rs.getObject(1, UUID::class.java), rs.getString(2),
                    rs.getObject(3, java.time.OffsetDateTime::class.java)?.toInstant(), rs.getInt(4), rs.getLong(5),
                    rs.getString(6), rs.getString(7), rs.getLong(8), rs.getString(9),
                )
            }
        }
    }

    fun events(id: UUID): List<EventRow> = db.tx { conn ->
        conn.prepareStatement(
            "SELECT seq, type, from_state, to_state, outcome FROM payment_event WHERE payment_id = ? ORDER BY seq",
        ).use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(EventRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5))) }
            }
        }
    }

    /** Outbox rows in event order: (kind, payload). */
    fun outbox(id: UUID): List<Pair<String, String>> = db.tx { conn ->
        conn.prepareStatement("SELECT kind, payload::text FROM outbox WHERE payment_id = ? ORDER BY event_seq, id").use { ps ->
            ps.setObject(1, id)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
        }
    }

    /** The states partners were told about, in order. */
    fun partnerStatuses(id: UUID): List<String> =
        outbox(id).filter { it.first == "PARTNER_STATUS" }.map { Regex("\"state\": ?\"(\\w+)\"").find(it.second)!!.groupValues[1] }

    fun count(sql: String): Long = db.tx { conn ->
        conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
    }

    fun inboxStatuses(): List<String> = db.tx { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT status FROM provider_inbox ORDER BY id").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
    }

    fun addPartner(ref: String): UUID = db.tx { conn ->
        val id = UUID.randomUUID()
        conn.prepareStatement("INSERT INTO partner (id, external_ref, name) VALUES (?, ?, ?)").use { ps ->
            ps.setObject(1, id)
            ps.setString(2, ref)
            ps.setString(3, "Test partner $ref")
            ps.executeUpdate()
        }
        id
    }

    fun requestCancellation(paymentId: UUID) = db.tx { conn ->
        conn.prepareStatement("INSERT INTO cancellation_request (id, payment_id, requested_by) VALUES (?, ?, 'partner')").use { ps ->
            ps.setObject(1, UUID.randomUUID())
            ps.setObject(2, paymentId)
            ps.executeUpdate()
        }
    }
}

/** Runs [work] every [step] until [duration] has passed on the clock, the way a polling worker would. */
fun MutableClock.keepRunning(duration: Duration, step: Duration = Duration.ofMinutes(1), work: () -> Unit) {
    val end = now.plus(duration)
    while (now.isBefore(end)) {
        work()
        advance(step)
    }
    work()
}
