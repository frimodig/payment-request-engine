package engine

import java.security.MessageDigest
import java.sql.Connection
import java.time.Clock
import java.util.UUID

/** One payment instruction as a partner submits it. Identification fields are references, never real personal data in tests. */
data class Instruction(
    val endToEndId: String,
    val amount: String,
    val currency: String,
    val debtorReference: String? = null,
    val creditorReference: String? = null,
    val remittanceReference: String? = null,
)

sealed interface InstructionResult {
    data class Accepted(val paymentId: UUID) : InstructionResult

    /** Same partner and end-to-end id with identical content: a no-op, the earlier payment stands. */
    data class Duplicate(val paymentId: UUID) : InstructionResult

    /** Same end-to-end id with different content: rejected explicitly, never a silent edit. */
    data class Conflict(val existingPaymentId: UUID) : InstructionResult

    data class Rejected(val reason: String) : InstructionResult
}

class UnknownPartner(ref: String) : RuntimeException("Unknown partner '$ref'")

/**
 * Both front doors (file and API) end here: every submission becomes individual instructions, each
 * deduplicated on (partner, end-to-end id). Partial acceptance only; all-or-nothing is not in the PoC.
 */
class Ingestion(private val db: Db, private val clock: Clock) {

    fun submit(partnerRef: String, messageId: String, instructions: List<Instruction>, channel: String = "API"): List<InstructionResult> =
        db.tx { conn ->
            val partnerId = partnerId(conn, partnerRef) ?: throw UnknownPartner(partnerRef)
            val submissionId = submissionId(conn, partnerId, messageId, channel)
            // Insert in a fixed order: two submissions with the same instructions in different orders would
            // otherwise lock each other's rows and deadlock instead of deduplicating. Results keep the caller's order.
            val results = arrayOfNulls<InstructionResult>(instructions.size)
            for (i in instructions.indices.sortedBy { instructions[it].endToEndId }) {
                results[i] = ingest(conn, partnerId, submissionId, messageId, instructions[i])
            }
            conn.prepareStatement("UPDATE submission SET status = 'SPLIT' WHERE id = ?").use { ps ->
                ps.setObject(1, submissionId)
                ps.executeUpdate()
            }
            results.map { checkNotNull(it) }
        }

    private fun ingest(
        conn: Connection, partnerId: UUID, submissionId: UUID, correlationId: String, instruction: Instruction,
    ): InstructionResult {
        if (instruction.endToEndId.isBlank()) return InstructionResult.Rejected("Missing end-to-end id")
        val money = try {
            Money.parse(instruction.amount, instruction.currency)
        } catch (e: IllegalArgumentException) {
            return InstructionResult.Rejected(e.message ?: "Invalid amount")
        }
        val hash = contentHash(instruction, money)
        val now = clock.instant()
        val id = UUID.randomUUID()

        val inserted = conn.prepareStatement(
            """
            INSERT INTO payment_request (id, partner_id, submission_id, end_to_end_id, content_hash, state,
                                         amount_minor, currency, debtor_identification, creditor_identification,
                                         remittance_reference, next_action_at, event_seq, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, 'RECEIVED', ?, ?, ?, ?, ?, ?, 1, ?, ?)
            ON CONFLICT (partner_id, end_to_end_id) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, partnerId)
            ps.setObject(3, submissionId)
            ps.setString(4, instruction.endToEndId)
            ps.setString(5, hash)
            ps.setLong(6, money.minor)
            ps.setString(7, money.currency)
            ps.setString(8, instruction.debtorReference)
            ps.setString(9, instruction.creditorReference)
            ps.setString(10, instruction.remittanceReference)
            ps.setInstant(11, now)
            ps.setInstant(12, now)
            ps.setInstant(13, now)
            ps.executeUpdate()
        }
        if (inserted == 0) return existing(conn, partnerId, instruction.endToEndId, hash)

        // Sequence 1 is the arrival itself; the partner's acknowledgement is the response to this call.
        conn.prepareStatement(
            """
            INSERT INTO payment_event (payment_id, seq, type, from_state, to_state, outcome, category, actor_type,
                                       actor_id, correlation_id, occurred_at, recorded_at)
            VALUES (?, 1, 'RECEIVED', NULL, 'RECEIVED', 'APPLIED', 'BUSINESS', 'PARTNER', ?, ?, ?, ?)
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, partnerId.toString())
            ps.setString(3, correlationId)
            ps.setInstant(4, now)
            ps.setInstant(5, now)
            ps.executeUpdate()
        }
        return InstructionResult.Accepted(id)
    }

    private fun existing(conn: Connection, partnerId: UUID, endToEndId: String, hash: String): InstructionResult =
        conn.prepareStatement("SELECT id, content_hash FROM payment_request WHERE partner_id = ? AND end_to_end_id = ?").use { ps ->
            ps.setObject(1, partnerId)
            ps.setString(2, endToEndId)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "Conflicting insert but no existing row for $endToEndId" }
                val existingId = rs.getObject(1, UUID::class.java)
                if (rs.getString(2) == hash) InstructionResult.Duplicate(existingId) else InstructionResult.Conflict(existingId)
            }
        }

    private fun partnerId(conn: Connection, ref: String): UUID? =
        conn.prepareStatement("SELECT id FROM partner WHERE external_ref = ?").use { ps ->
            ps.setString(1, ref)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1, UUID::class.java) else null }
        }

    // A resend of the same message id is allowed: its instructions are deduplicated one by one.
    private fun submissionId(conn: Connection, partnerId: UUID, messageId: String, channel: String): UUID {
        val id = UUID.randomUUID()
        val inserted = conn.prepareStatement(
            """
            INSERT INTO submission (id, partner_id, channel, message_id, received_at) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (partner_id, message_id) DO NOTHING
            """.trimIndent(),
        ).use { ps ->
            ps.setObject(1, id)
            ps.setObject(2, partnerId)
            ps.setString(3, channel)
            ps.setString(4, messageId)
            ps.setInstant(5, clock.instant())
            ps.executeUpdate()
        }
        if (inserted == 1) return id
        return conn.prepareStatement("SELECT id FROM submission WHERE partner_id = ? AND message_id = ?").use { ps ->
            ps.setObject(1, partnerId)
            ps.setString(2, messageId)
            ps.executeQuery().use { rs ->
                check(rs.next())
                rs.getObject(1, UUID::class.java)
            }
        }
    }

    // Hash of the parsed amount, so "12.5" and "12.50" are the same content.
    private fun contentHash(i: Instruction, money: Money): String {
        // Length-prefixed, with an absent field marked apart from an empty one, so no two different instructions
        // can produce the same text.
        val canonical = listOf(
            i.endToEndId, money.minor.toString(), money.currency,
            i.debtorReference, i.creditorReference, i.remittanceReference,
        ).joinToString("") { if (it == null) "-;" else "${it.length}:$it;" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
