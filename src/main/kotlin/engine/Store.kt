package engine

import java.sql.Connection
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A payment as the worker sees it while holding its lease. */
data class Payment(
    val id: UUID,
    val endToEndId: String,
    val context: Context,
    val amount: Money,
    val downstream: String?,
    val downstreamRef: String?,
    val sendAttempt: Int,
    val nextActionAt: Instant?,
    val leaseToken: Long,
    val eventSeq: Int,
) {
    val state: State get() = context.state

    /** Derived from payment and attempt, never from the worker: a takeover resends under the same key. */
    val idempotencyKey: String get() = "$id:$sendAttempt"
}

/** The fenced write found that the lease was taken over, or the payment changed since it was read. */
class LeaseLost(message: String) : RuntimeException(message)

data class Actor(val type: String, val id: String)

/** One state change: everything written in a single transaction, or nothing. */
data class Change(
    val type: String,
    val actor: Actor,
    val steps: List<Step>,
    val context: Context,
    /** New due time; null means no automatic work. */
    val nextActionAt: Instant?,
    val inboxEventId: Long? = null,
    val cancellationId: UUID? = null,
    val sendAttempt: Int? = null,
    val downstream: String? = null,
    val downstreamRef: String? = null,
    /** JSON without personal data. */
    val payload: String? = null,
    /** BUSINESS or TECHNICAL; null derives it from the steps. Bookkeeping that changes no state is TECHNICAL. */
    val category: String? = null,
)

data class InboxEvent(val id: Long, val status: ProviderStatus)

class Store(private val db: Db, private val clock: Clock, private val leaseFor: Duration) {

    /**
     * Claims one payment whose due time passed, or that has an unapplied input, and whose lease is free or
     * expired. `FOR UPDATE SKIP LOCKED` in this one short statement; no row lock is held afterwards.
     */
    fun claim(workerId: String): Payment? = db.tx { conn ->
        val now = clock.instant()
        conn.prepareStatement(
            """
            UPDATE payment_request p
               SET lease_owner = ?, lease_until = ?, lease_token = p.lease_token + 1, updated_at = ?
             WHERE p.id = (
                   SELECT q.id FROM payment_request q
                    WHERE (q.lease_until IS NULL OR q.lease_until <= ?)
                      AND (q.next_action_at <= ?
                           OR EXISTS (SELECT 1 FROM provider_inbox i WHERE i.payment_id = q.id AND i.status = 'NEW')
                           OR EXISTS (SELECT 1 FROM cancellation_request c WHERE c.payment_id = q.id AND c.applied_at IS NULL))
                    ORDER BY (EXISTS (SELECT 1 FROM provider_inbox i WHERE i.payment_id = q.id AND i.status = 'NEW')
                              OR EXISTS (SELECT 1 FROM cancellation_request c WHERE c.payment_id = q.id AND c.applied_at IS NULL)) DESC,
                             q.next_action_at, q.created_at
                    LIMIT 1
                      FOR UPDATE SKIP LOCKED)
            RETURNING $COLUMNS
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, workerId)
            ps.setInstant(2, now.plus(leaseFor))
            ps.setInstant(3, now)
            ps.setInstant(4, now)
            ps.setInstant(5, now)
            ps.executeQuery().use { rs -> if (rs.next()) rs.toPayment() else null }
        }
    }

    /**
     * The fenced write. Compare-and-set on the lease token and the event sequence, so a slow worker
     * cannot overwrite a takeover and nothing else can have written in between. Every successful write
     * renews the lease. Payment row, events, outbox rows and the consumed input go in one transaction.
     */
    fun apply(p: Payment, change: Change): Payment = db.tx { conn ->
        val now = clock.instant()
        val newSeq = p.eventSeq + change.steps.size
        conn.prepareStatement(
            """
            UPDATE payment_request
               SET state = ?, downstream_position = ?, cancel_requested = ?, event_seq = ?, next_action_at = ?,
                   lease_until = ?, send_attempt = COALESCE(?, send_attempt), downstream = COALESCE(?, downstream),
                   downstream_ref = COALESCE(?, downstream_ref), updated_at = ?
             WHERE id = ? AND lease_token = ? AND event_seq = ?
            """.trimIndent(),
        ).use { ps ->
            ps.setString(1, change.context.state.name)
            ps.setString(2, change.context.position?.name)
            ps.setBoolean(3, change.context.cancelRequested)
            ps.setInt(4, newSeq)
            ps.setInstant(5, change.nextActionAt)
            ps.setInstant(6, now.plus(leaseFor))
            if (change.sendAttempt == null) ps.setObject(7, null) else ps.setInt(7, change.sendAttempt)
            ps.setString(8, change.downstream)
            ps.setString(9, change.downstreamRef)
            ps.setInstant(10, now)
            ps.setObject(11, p.id)
            ps.setLong(12, p.leaseToken)
            ps.setInt(13, p.eventSeq)
            if (ps.executeUpdate() != 1) {
                throw LeaseLost("Lease or sequence moved on for payment ${p.id} (token ${p.leaseToken}, seq ${p.eventSeq})")
            }
        }
        change.steps.forEachIndexed { index, step ->
            val seq = p.eventSeq + 1 + index
            insertEvent(conn, p.id, seq, change, step, now)
            step.effects.forEach { insertOutbox(conn, p.id, seq, it, now) }
        }
        change.inboxEventId?.let { consumeInbox(conn, it) }
        change.cancellationId?.let { consumeCancellation(conn, it, now) }
        change.downstreamRef?.let { relinkParked(conn, p.id, change.downstream ?: p.downstream, it) }
        p.copy(
            context = change.context,
            downstream = change.downstream ?: p.downstream,
            downstreamRef = change.downstreamRef ?: p.downstreamRef,
            sendAttempt = change.sendAttempt ?: p.sendAttempt,
            nextActionAt = change.nextActionAt,
            eventSeq = newSeq,
        )
    }

    /** Frees the lease. Fenced on the token only: a worker that lost its lease releases nothing. */
    fun release(p: Payment) {
        db.tx { conn ->
            conn.prepareStatement(
                "UPDATE payment_request SET lease_owner = NULL, lease_until = NULL WHERE id = ? AND lease_token = ?",
            ).use { ps ->
                ps.setObject(1, p.id)
                ps.setLong(2, p.leaseToken)
                ps.executeUpdate()
            }
        }
    }

    /**
     * The run of [types] events at the end of the payment's history: how many there are and when the first happened.
     * The run ends at any other event that changed something, or at a status answer even when it changed nothing,
     * because the downstream answered. A repeated webhook that changed nothing does not end it: webhooks can arrive
     * while status queries fail. Null when there is no run.
     */
    fun currentStreak(paymentId: UUID, types: Set<String>): Streak? = db.tx { conn ->
        conn.prepareStatement(
            """
            SELECT count(*), min(occurred_at) FROM payment_event
             WHERE payment_id = ? AND type = ANY (?)
               AND seq > COALESCE((SELECT max(seq) FROM payment_event
                                    WHERE payment_id = ? AND NOT type = ANY (?)
                                      AND (outcome <> 'STALE' OR type = 'STATUS_QUERY')), 0)
            """.trimIndent(),
        ).use { ps ->
            val typeArray = conn.createArrayOf("text", types.toTypedArray())
            ps.setObject(1, paymentId)
            ps.setArray(2, typeArray)
            ps.setObject(3, paymentId)
            ps.setArray(4, typeArray)
            ps.executeQuery().use { rs ->
                rs.next()
                val count = rs.getInt(1)
                if (count == 0) null else Streak(count, rs.getObject(2, java.time.OffsetDateTime::class.java).toInstant())
            }
        }
    }

    fun pendingInbox(paymentId: UUID): List<InboxEvent> = db.tx { conn ->
        conn.prepareStatement(
            "SELECT id, payload->>'status' FROM provider_inbox WHERE payment_id = ? AND status = 'NEW' ORDER BY id",
        ).use { ps ->
            ps.setObject(1, paymentId)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(InboxEvent(rs.getLong(1), ProviderStatus.valueOf(rs.getString(2)))) }
            }
        }
    }

    fun pendingCancellations(paymentId: UUID): List<UUID> = db.tx { conn ->
        conn.prepareStatement(
            "SELECT id FROM cancellation_request WHERE payment_id = ? AND applied_at IS NULL ORDER BY requested_at",
        ).use { ps ->
            ps.setObject(1, paymentId)
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getObject(1, UUID::class.java)) } }
        }
    }

    private fun insertEvent(conn: Connection, paymentId: UUID, seq: Int, change: Change, step: Step, now: Instant) {
        conn.prepareStatement(
            """
            INSERT INTO payment_event (payment_id, seq, type, from_state, to_state, outcome, category, actor_type,
                                       actor_id, inbox_event_id, payload, occurred_at, recorded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, paymentId)
            ps.setInt(2, seq)
            ps.setString(3, change.type)
            ps.setString(4, step.from.name)
            ps.setString(5, step.to.name)
            ps.setString(6, step.outcome.name)
            // Stale repeats are technical noise; the events an auditor needs are the ones that changed something.
            ps.setString(7, change.category ?: if (step.outcome == Outcome.STALE) "TECHNICAL" else "BUSINESS")
            ps.setString(8, change.actor.type)
            ps.setString(9, change.actor.id)
            if (change.inboxEventId == null) ps.setObject(10, null) else ps.setLong(10, change.inboxEventId)
            ps.setString(11, change.payload)
            ps.setInstant(12, now)
            ps.setInstant(13, now)
            ps.executeUpdate()
        }
    }

    private fun insertOutbox(conn: Connection, paymentId: UUID, seq: Int, effect: Effect, now: Instant) {
        val (kind, payload) = effect.toOutboxRow()
        conn.prepareStatement(
            "INSERT INTO outbox (payment_id, event_seq, kind, payload, next_attempt_at) VALUES (?, ?, ?, ?::jsonb, ?)",
        ).use { ps ->
            ps.setObject(1, paymentId)
            ps.setInt(2, seq)
            ps.setString(3, kind)
            ps.setString(4, payload)
            ps.setInstant(5, now)
            ps.executeUpdate()
        }
    }

    private fun consumeInbox(conn: Connection, id: Long) {
        conn.prepareStatement("UPDATE provider_inbox SET status = 'APPLIED' WHERE id = ? AND status = 'NEW'").use { ps ->
            ps.setLong(1, id)
            check(ps.executeUpdate() == 1) { "Inbox event $id was not NEW" }
        }
    }

    private fun consumeCancellation(conn: Connection, id: UUID, now: Instant) {
        conn.prepareStatement("UPDATE cancellation_request SET applied_at = ? WHERE id = ? AND applied_at IS NULL").use { ps ->
            ps.setInstant(1, now)
            ps.setObject(2, id)
            check(ps.executeUpdate() == 1) { "Cancellation request $id was already applied" }
        }
    }

    // Storing the downstream reference makes events that arrived earlier matchable.
    private fun relinkParked(conn: Connection, paymentId: UUID, provider: String?, ref: String) {
        // Held until commit, so a concurrent ProviderInbox.receive either sees this reference or is seen by this update.
        conn.lockReference(provider.orEmpty(), ref)
        conn.prepareStatement(
            "UPDATE provider_inbox SET payment_id = ?, status = 'NEW' WHERE status = 'PARKED' AND provider = ? AND downstream_ref = ?",
        ).use { ps ->
            ps.setObject(1, paymentId)
            ps.setString(2, provider)
            ps.setString(3, ref)
            ps.executeUpdate()
        }
    }

    private fun Effect.toOutboxRow(): Pair<String, String> = when (this) {
        is Effect.PartnerStatus -> "PARTNER_STATUS" to """{"state":"${state.name}"}"""
        Effect.ForwardCancellation -> "FORWARD_CANCELLATION" to "{}"
        Effect.EnqueueReconciliation -> "RECONCILIATION" to "{}"
        is Effect.RaiseAlert -> "ALERT" to """{"reason":"${reason.name}"}"""
    }

    private fun ResultSet.toPayment() = Payment(
        id = getObject("id", UUID::class.java),
        endToEndId = getString("end_to_end_id"),
        context = Context(
            state = State.valueOf(getString("state")),
            position = getString("downstream_position")?.let { State.valueOf(it) },
            cancelRequested = getBoolean("cancel_requested"),
        ),
        amount = Money(getLong("amount_minor"), getString("currency")),
        downstream = getString("downstream"),
        downstreamRef = getString("downstream_ref"),
        sendAttempt = getInt("send_attempt"),
        nextActionAt = getObject("next_action_at", java.time.OffsetDateTime::class.java)?.toInstant(),
        leaseToken = getLong("lease_token"),
        eventSeq = getInt("event_seq"),
    )

    private companion object {
        const val COLUMNS = "p.id, p.end_to_end_id, p.state, p.downstream_position, p.cancel_requested, p.amount_minor, " +
            "p.currency, p.downstream, p.downstream_ref, p.send_attempt, p.next_action_at, p.lease_token, p.event_seq"
    }
}

/** A run of events of the same kind, such as the failed calls of one downstream outage. */
data class Streak(val count: Int, val startedAt: Instant)
