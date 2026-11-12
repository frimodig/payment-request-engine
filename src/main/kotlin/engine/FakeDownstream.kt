package engine

import java.util.concurrent.ConcurrentLinkedDeque

/** The ways the fake can misbehave on the next send. Scripted one call at a time. */
sealed interface SendBehavior {
    data object Normal : SendBehavior

    /** Takes [millis] before answering. */
    data class Delay(val millis: Long) : SendBehavior

    /** Processes the payment, then the response is lost: the caller sees a timeout. */
    data object TimeoutAfterProcessing : SendBehavior

    /** The request never arrives: the caller sees a timeout and the downstream has nothing. */
    data object TimeoutBeforeProcessing : SendBehavior

    data class Reject(val reasonCode: String) : SendBehavior

    /** Accepts the payment but answers with this reference, however odd it is. */
    data class AcceptWithRef(val ref: String) : SendBehavior
}

class DownstreamTimeout(message: String) : RuntimeException(message)

/**
 * One fake downstream that can be told to delay, time out, reject, duplicate and reorder. It is
 * idempotent on the idempotency key, like a real one should be, and counts what it really processed.
 */
class FakeDownstream(override val name: String = "fake") : Downstream {
    private class Record(val ref: String, val request: SendRequest, var status: ProviderStatus?)

    private val lock = Any()
    private val records = LinkedHashMap<String, Record>()
    private val script = ConcurrentLinkedDeque<SendBehavior>()
    private var nextRef = 1
    private var nextEvent = 1

    @Volatile var statusAvailable = true

    /** Makes [status] throw instead of answering, like a broken network adapter. */
    @Volatile var statusThrows = false

    private val sendCalls = java.util.concurrent.atomic.AtomicInteger()

    /** Every call to [send], including repeats of the same key and calls that timed out. */
    val sendCallCount: Int get() = sendCalls.get()

    /** Payments the downstream really processed: one per idempotency key, whatever the number of calls. */
    val processedCount: Int get() = synchronized(lock) { records.size }

    fun processedKeys(): List<String> = synchronized(lock) { records.keys.toList() }

    /** What the downstream actually received for a key, to check that nothing changed on the way. */
    fun requestFor(idempotencyKey: String): SendRequest? = synchronized(lock) { records[idempotencyKey]?.request }

    /** Queues behaviours for the next sends, consumed in order; after that sends behave normally. */
    fun script(vararg behaviors: SendBehavior) = behaviors.forEach { script.addLast(it) }

    override fun send(request: SendRequest): SendResult {
        sendCalls.incrementAndGet()
        val behavior = script.pollFirst() ?: SendBehavior.Normal
        when (behavior) {
            SendBehavior.TimeoutBeforeProcessing -> throw DownstreamTimeout("no response")
            is SendBehavior.Delay -> Thread.sleep(behavior.millis)
            is SendBehavior.Reject -> return SendResult.Rejected(behavior.reasonCode)
            SendBehavior.Normal, SendBehavior.TimeoutAfterProcessing, is SendBehavior.AcceptWithRef -> Unit
        }
        val ref = synchronized(lock) {
            records.getOrPut(request.idempotencyKey) {
                Record((behavior as? SendBehavior.AcceptWithRef)?.ref ?: "REF-${nextRef++}", request, null)
            }.ref
        }
        if (behavior == SendBehavior.TimeoutAfterProcessing) throw DownstreamTimeout("response lost")
        return SendResult.Accepted(ref)
    }

    /** The downstream loses its record of a key, like one whose idempotency window has passed. */
    fun forget(idempotencyKey: String) {
        synchronized(lock) { records.remove(idempotencyKey) }
    }

    override fun status(idempotencyKey: String): StatusResult {
        if (statusThrows) throw DownstreamTimeout("status call failed")
        if (!statusAvailable) return StatusResult.Unavailable
        val record = synchronized(lock) { records[idempotencyKey] } ?: return StatusResult.NotFound
        return StatusResult.Known(record.ref, record.status)
    }

    /** Produces the next event for a payment the downstream knows, and moves the payment's own status. */
    fun event(idempotencyKey: String, status: ProviderStatus): ProviderEvent = synchronized(lock) {
        val record = checkNotNull(records[idempotencyKey]) { "Downstream does not know $idempotencyKey" }
        record.status = status
        ProviderEvent(name, "EVT-${nextEvent++}", record.ref, status)
    }

    /** An event for a reference the downstream never gave us, to test parking. */
    fun eventForUnknownRef(ref: String, status: ProviderStatus): ProviderEvent =
        synchronized(lock) { ProviderEvent(name, "EVT-${nextEvent++}", ref, status) }

    /** Delivers events to the inbox the way a misbehaving downstream would. */
    fun deliver(inbox: ProviderInbox, events: List<ProviderEvent>, duplicate: Boolean = false, reorder: Boolean = false) {
        val ordered = if (reorder) events.reversed() else events
        ordered.forEach { event ->
            inbox.receive(event)
            if (duplicate) inbox.receive(event)
        }
    }
}
