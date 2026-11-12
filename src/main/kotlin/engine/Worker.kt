package engine

import engine.Input.Advance
import engine.Input.CancelRequested
import engine.Input.ProviderReported
import engine.Input.ResponseTimeout
import java.time.Clock
import java.time.Duration
import java.time.Instant

class WorkerConfig(
    val workerId: String,
    /** How long a payment may wait for a downstream answer before the worker acts on the silence. */
    val responseWindow: Duration = Duration.ofMinutes(5),
    /** How long to wait before asking an unavailable status endpoint again, and how many times, before UNKNOWN. */
    val statusRetryDelay: Duration = Duration.ofSeconds(30),
    val statusRetries: Int = 3,
)

/**
 * Claims one due payment, applies its pending inputs and at most one timed step, one transaction per
 * state change, and releases it. Only the lease holder ever changes a payment.
 *
 * [afterSendRecorded] is a test seam: it runs after SENDING is committed and before the downstream call,
 * which is where a worker can stall or die.
 */
class Worker(
    private val store: Store,
    private val downstream: Downstream,
    private val clock: Clock,
    private val config: WorkerConfig,
    private val afterSendRecorded: (Payment) -> Unit = {},
) {
    private val worker = Actor("SERVICE", config.workerId)

    /**
     * Returns false when nothing was due. Throws [LeaseLost] if the lease was taken over mid-work.
     * On any failure the lease is deliberately not released: it expires on its own, like a crashed worker's,
     * which is what lets another worker take over and stops a failing payment being retried in a hot loop.
     */
    fun runOnce(): Boolean {
        var p = store.claim(config.workerId) ?: return false
        // Claimed for an input, not for its due time: a takeover may not know the reference the downstream gave
        // the previous holder. Learn it before acting. (When due, the timed step does this.)
        if (!isDue(p)) p = learnReference(p, restartWindow = false)
        p = applyInbox(p)
        p = applyCancellations(p)
        if (isDue(p)) p = timedStep(p)
        store.release(p)
        return true
    }

    private fun isDue(p: Payment) = p.nextActionAt?.let { !it.isAfter(clock.instant()) } ?: false

    private fun applyInbox(start: Payment): Payment =
        store.pendingInbox(start.id).fold(start) { p, event ->
            transition(
                p, ProviderReported(event.status), "PROVIDER_EVENT", Actor("DOWNSTREAM", p.downstream ?: "unknown"),
                inboxEventId = event.id,
            )
        }

    private fun applyCancellations(start: Payment): Payment =
        store.pendingCancellations(start.id).fold(start) { p, id ->
            transition(p, CancelRequested, "CANCEL_REQUESTED", Actor("PARTNER", "cancellation"), cancellationId = id)
        }

    private fun timedStep(p: Payment): Payment = when (p.state) {
        State.RECEIVED -> transition(p, Advance(State.VALIDATED), "VALIDATED", worker)
        State.VALIDATED -> transition(p, Advance(State.PENDING_SCREENING), "SCREENING_STARTED", worker)
        State.PENDING_SCREENING -> transition(p, Advance(State.READY_TO_SEND), "SCREENING_PASSED", worker)
        State.READY_TO_SEND -> startSending(p)
        State.SENDING -> checkSending(p)
        State.PRESENTED, State.ACCEPTED, State.CANCELLATION_REQUESTED -> {
            // Silence is only silence if we know what to wait for: a previous holder may have been cut off before
            // it stored the reference. Learning it restarts the window instead of timing out.
            val learned = learnReference(p)
            if (learned.downstreamRef != p.downstreamRef) learned else transition(p, ResponseTimeout, "RESPONSE_TIMEOUT", worker)
        }
        // Terminal and waiting-on-a-person states have no due time, so they are never due here.
        else -> p
    }

    // SENDING is recorded, with its attempt number and due time, before the downstream call. A worker that
    // dies after this point leaves a payment the next worker knows was in flight.
    private fun startSending(p: Payment): Payment {
        val sending = transition(
            p, Advance(State.SENDING), "SENDING", worker,
            sendAttempt = p.sendAttempt + 1, downstream = downstream.name,
        )
        afterSendRecorded(sending)
        return sendAndRecord(sending)
    }

    private fun sendAndRecord(p: Payment): Payment {
        val result = try {
            downstream.send(SendRequest(p.idempotencyKey, p.id, p.amount, p.endToEndId))
        } catch (e: Exception) {
            // Whatever went wrong, the outcome is unknown, never failed. Query the status if possible: if the
            // downstream has the payment we learn its reference and keep waiting; otherwise UNKNOWN, which queues
            // reconciliation. Only the exception class is kept: its message could carry data from the downstream.
            val payload = """{"error":${jsonString(e.javaClass.simpleName)}}"""
            return when (val known = downstream.status(p.idempotencyKey)) {
                is StatusResult.Known -> recordReference(p, known.downstreamRef, "SEND_TIMEOUT_RECOVERED")
                StatusResult.Unavailable -> statusUnavailable(p, "SEND_TIMEOUT", payload)
                StatusResult.NotFound -> transition(p, ResponseTimeout, "SEND_TIMEOUT", worker, payload = payload)
            }
        }
        return when (result) {
            is SendResult.Accepted -> recordReference(p, result.downstreamRef, "SEND_RESPONSE")
            is SendResult.Rejected -> transition(
                p, ProviderReported(ProviderStatus.REJECTED), "SEND_REJECTED", Actor("DOWNSTREAM", downstream.name),
                payload = """{"reasonCode":${jsonString(cleanReasonCode(result.reasonCode))}}""",
            )
        }
    }

    // The response window passed in SENDING, or a worker took the payment over mid-send. Ask the downstream by the
    // same idempotency key: never a blind new send.
    private fun checkSending(p: Payment): Payment = when (val status = downstream.status(p.idempotencyKey)) {
        is StatusResult.Known -> {
            val knew = p.downstreamRef != null
            var q = if (knew) p else recordReference(p, status.downstreamRef, "STATUS_QUERY")
            when {
                status.status != null ->
                    q = transition(q, ProviderReported(status.status), "STATUS_QUERY", Actor("DOWNSTREAM", downstream.name))
                knew -> q = transition(q, ResponseTimeout, "RESPONSE_TIMEOUT", worker)
            }
            q
        }
        // The downstream never saw it: resending under the same key is safe, the key deduplicates.
        StatusResult.NotFound -> sendAndRecord(p)
        StatusResult.Unavailable -> statusUnavailable(p, "RESPONSE_TIMEOUT", null)
    }

    // The downstream could not answer a status query. One outage is not yet a reason for a person to look: ask again
    // a few times, staying in SENDING, and only then treat the silence as UNKNOWN. [type] and [payload] describe the
    // timeout that is recorded if the retries are used up.
    private fun statusUnavailable(p: Payment, type: String, payload: String?): Payment {
        if (store.consecutiveEvents(p.id, "STATUS_UNAVAILABLE") >= config.statusRetries) {
            return transition(p, ResponseTimeout, type, worker, payload = payload)
        }
        return store.apply(
            p,
            Change(
                type = "STATUS_UNAVAILABLE",
                actor = Actor("DOWNSTREAM", downstream.name),
                steps = listOf(Step(p.state, p.state, Outcome.STALE)),
                context = p.context,
                nextActionAt = clock.instant().plus(config.statusRetryDelay),
            ),
        )
    }

    // A downstream's reason code is stored as it came, minus what the database cannot hold (control characters such as
    // NUL) and bounded, so a strange code can never leave a payment stuck in SENDING.
    private fun cleanReasonCode(code: String): String = code.filterNot { it.isISOControl() }.take(64)

    // A payment that was sent but has no stored reference: ask the downstream by the idempotency key.
    private fun learnReference(p: Payment, restartWindow: Boolean = true): Payment {
        if (p.downstreamRef != null || p.sendAttempt == 0) return p
        if (p.state != State.SENDING && p.state != State.CANCELLATION_REQUESTED) return p
        val known = downstream.status(p.idempotencyKey) as? StatusResult.Known ?: return p
        return recordReference(p, known.downstreamRef, "STATUS_QUERY", restartWindow)
    }

    // Not a state change: stores the downstream reference so unsolicited events can be matched. Restarts the response
    // window, except when the payment was claimed only for an input: then it is not due, and its due time stays.
    private fun recordReference(p: Payment, ref: String, type: String, restartWindow: Boolean = true): Payment =
        store.apply(
            p,
            Change(
                type = type,
                actor = Actor("DOWNSTREAM", downstream.name),
                steps = listOf(Step(p.state, p.state, Outcome.APPLIED)),
                context = p.context,
                nextActionAt = if (restartWindow) clock.instant().plus(config.responseWindow) else p.nextActionAt,
                downstreamRef = ref,
                category = "TECHNICAL",
            ),
        )

    private fun transition(
        p: Payment,
        input: Input,
        type: String,
        actor: Actor,
        inboxEventId: Long? = null,
        cancellationId: java.util.UUID? = null,
        sendAttempt: Int? = null,
        downstream: String? = null,
        payload: String? = null,
    ): Payment {
        val result = when (val t = PaymentStateMachine.transition(p.context, input)) {
            is Transition.Valid -> t
            is Transition.Invalid -> error("Worker bug: ${t.reason} for payment ${p.id}")
        }
        // A step that leaves the state alone (stale event, position update) must not move the due time:
        // otherwise a stream of repeated events could postpone the timeout forever.
        val due = if (result.state == p.state) p.nextActionAt else scheduleFor(result.state, clock.instant())
        return store.apply(
            p,
            Change(
                type = type, actor = actor, steps = result.steps, context = result.context, nextActionAt = due,
                inboxEventId = inboxEventId, cancellationId = cancellationId, sendAttempt = sendAttempt,
                downstream = downstream, payload = payload,
            ),
        )
    }

    // Due now while we do the work ourselves, after the response window while we wait on the downstream.
    private fun scheduleFor(state: State, now: Instant): Instant? = when {
        !state.hasAutomaticWork -> null
        state.waitsForDownstream -> now.plus(config.responseWindow)
        else -> now
    }
}
