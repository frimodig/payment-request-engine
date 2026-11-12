package engine

import java.time.Clock
import java.util.UUID

data class SendRequest(val idempotencyKey: String, val paymentId: UUID, val amount: Money, val endToEndId: String)

sealed interface SendResult {
    data class Accepted(val downstreamRef: String) : SendResult
    data class Rejected(val reasonCode: String) : SendResult
}

sealed interface StatusResult {
    /** [status] is null while the downstream knows the payment but has not progressed it. */
    data class Known(val downstreamRef: String, val status: ProviderStatus?) : StatusResult
    data object NotFound : StatusResult
    data object Unavailable : StatusResult
}

/** The anti-corruption boundary: one adapter per downstream system. */
interface Downstream {
    val name: String

    /** Idempotent on [SendRequest.idempotencyKey]. Any exception means the outcome is unknown, never "failed". */
    fun send(request: SendRequest): SendResult

    fun status(idempotencyKey: String): StatusResult
}

/** A raw downstream event as it arrives, before any matching. */
data class ProviderEvent(val provider: String, val eventId: String, val downstreamRef: String, val status: ProviderStatus)

/**
 * Stores downstream events exactly as received and acknowledges them before any processing.
 * Duplicate deliveries are dropped here; unmatched events are parked, never dropped.
 */
class ProviderInbox(private val db: Db, private val clock: Clock) {

    /** Returns true if the event was new, false for a duplicate delivery. */
    fun receive(event: ProviderEvent): Boolean = db.tx { conn ->
        // Serialised with the transaction that stores the downstream reference (Store.apply): either this sees the
        // reference and matches, or that sees this parked row and links it. See lockReference.
        conn.lockReference(event.provider, event.downstreamRef)
        conn.prepareStatement(
            """
            INSERT INTO provider_inbox (provider, provider_event_id, provider_timestamp, received_at, downstream_ref,
                                        payment_id, status, payload)
            SELECT ?, ?, ?, ?, ?, p.id, CASE WHEN p.id IS NULL THEN 'PARKED' ELSE 'NEW' END, ?::jsonb
              FROM (SELECT 1) AS one
              LEFT JOIN payment_request p ON p.downstream = ? AND p.downstream_ref = ?
            ON CONFLICT (provider, provider_event_id) DO NOTHING
            RETURNING id
            """.trimIndent(),
        ).use { ps ->
            val now = clock.instant()
            ps.setString(1, event.provider)
            ps.setString(2, event.eventId)
            ps.setInstant(3, now)
            ps.setInstant(4, now)
            ps.setString(5, event.downstreamRef)
            ps.setString(6, """{"status":${jsonString(event.status.name)}}""")
            ps.setString(7, event.provider)
            ps.setString(8, event.downstreamRef)
            ps.executeQuery().use { rs -> rs.next() }
        }
    }
}
