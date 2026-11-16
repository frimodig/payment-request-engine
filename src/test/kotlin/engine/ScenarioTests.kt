package engine

import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Scenario tests against a real Postgres: the failure scenarios the PoC exists to prove. */
class ScenarioTests {
    private val clock = MutableClock()
    private val db = TestPostgres.db
    private val tables = Tables(db)
    private val window = Duration.ofMinutes(5)
    private val horizon = WorkerConfig("any").outageHorizon
    private val store = Store(db, clock, Duration.ofMinutes(1))
    private val ingestion = Ingestion(db, clock)
    private val inbox = ProviderInbox(db, clock)
    private val downstream = FakeDownstream()

    private class SimulatedCrash : RuntimeException("worker died")

    @BeforeTest
    fun reset() {
        TestPostgres.reset()
        tables.addPartner("P1")
    }

    private fun worker(id: String, hook: (Payment) -> Unit = {}) =
        Worker(store, downstream, clock, WorkerConfig(id, window), hook)

    private fun drain(w: Worker) {
        while (w.runOnce()) { /* keep going until nothing is due */ }
    }

    private fun submit(
        e2e: String, amount: String = "12.34", currency: String = "EUR", partner: String = "P1",
    ): UUID {
        val result = ingestion.submit(partner, "M-$e2e", listOf(Instruction(e2e, amount, currency))).single()
        return assertIs<InstructionResult.Accepted>(result).paymentId
    }

    private fun key(id: UUID, attempt: Int = 1) = "$id:$attempt"

    /** Ingests a payment and runs it to SENDING with the downstream reference recorded. */
    private fun sent(e2e: String = "E1"): UUID {
        val id = submit(e2e)
        drain(worker("setup"))
        assertEquals("SENDING", tables.payment(id).state)
        return id
    }

    private fun deliver(id: UUID, vararg statuses: ProviderStatus, duplicate: Boolean = false, reorder: Boolean = false) =
        downstream.deliver(inbox, statuses.map { downstream.event(key(id), it) }, duplicate, reorder)

    // --- Scenario: duplicate submission ---------------------------------------------------------

    @Test
    fun `duplicate submission is a no-op and a changed resend is a conflict`() {
        val first = assertIs<InstructionResult.Accepted>(
            ingestion.submit("P1", "M1", listOf(Instruction("E1", "12.34", "EUR"))).single(),
        )
        // Same content under a new message id, and with the amount written differently: still the same instruction.
        assertEquals(
            InstructionResult.Duplicate(first.paymentId),
            ingestion.submit("P1", "M2", listOf(Instruction("E1", "12.340", "EUR"))).single(),
        )
        // The same message resent as a whole.
        assertEquals(
            InstructionResult.Duplicate(first.paymentId),
            ingestion.submit("P1", "M1", listOf(Instruction("E1", "12.34", "EUR"))).single(),
        )
        // Same id, different content: rejected explicitly, and the stored payment is untouched.
        assertEquals(
            InstructionResult.Conflict(first.paymentId),
            ingestion.submit("P1", "M3", listOf(Instruction("E1", "99.00", "EUR"))).single(),
        )
        assertEquals(1, tables.count("SELECT count(*) FROM payment_request"))
        assertEquals(1234, tables.payment(first.paymentId).amountMinor)
        assertEquals(1, tables.events(first.paymentId).size)
    }

    @Test
    fun `the same end-to-end id from another partner is a different payment`() {
        tables.addPartner("P2")
        val a = submit("E1", partner = "P1")
        val b = submit("E1", partner = "P2")
        assertNotEquals(a, b)
    }

    @Test
    fun `simultaneous duplicate submissions create exactly one payment`() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val results = (1..8).map { i ->
                pool.submit<InstructionResult> {
                    ingestion.submit("P1", "M-race-$i", listOf(Instruction("E1", "12.34", "EUR"))).single()
                }
            }.map { it.get(30, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it is InstructionResult.Accepted })
            assertEquals(7, results.count { it is InstructionResult.Duplicate })
            assertEquals(1, tables.count("SELECT count(*) FROM payment_request"))
        } finally {
            pool.shutdownNow()
        }
    }

    // --- Scenario: out-of-order provider events -------------------------------------------------

    @Test
    fun `out-of-order and duplicated provider events end in PAID with each partner status once`() {
        val id = sent()
        // PAID arrives first, then ACCEPTED, then PRESENTED, each delivered twice.
        deliver(id, ProviderStatus.PRESENTED, ProviderStatus.ACCEPTED, ProviderStatus.PAID, duplicate = true, reorder = true)
        assertEquals(listOf("NEW", "NEW", "NEW"), tables.inboxStatuses(), "duplicate deliveries must be dropped")
        drain(worker("A"))

        val payment = tables.payment(id)
        assertEquals("PAID", payment.state)
        assertEquals(null, payment.nextActionAt)
        val providerEvents = tables.events(id).filter { it.type == "PROVIDER_EVENT" }
        assertEquals(
            listOf("INFERRED", "INFERRED", "APPLIED", "STALE", "STALE"),
            providerEvents.map { it.outcome },
            "PAID is applied with PRESENTED and ACCEPTED inferred; the late ones are recorded as stale",
        )
        assertEquals(listOf("PRESENTED", "ACCEPTED", "PAID"), tables.partnerStatuses(id))
        assertEquals(listOf("APPLIED", "APPLIED", "APPLIED"), tables.inboxStatuses())
    }

    @Test
    fun `a payment is PAID until settlement confirms it, and settlement arriving first is not lost`() {
        val inOrder = sent("E1")
        deliver(inOrder, ProviderStatus.PRESENTED, ProviderStatus.ACCEPTED, ProviderStatus.PAID)
        drain(worker("A"))
        val paid = tables.payment(inOrder)
        assertEquals("PAID", paid.state)
        assertEquals(null, paid.nextActionAt, "PAID waits for reconciliation, not for a timer")

        val reversed = sent("E2")
        deliver(reversed, ProviderStatus.PRESENTED, ProviderStatus.ACCEPTED, ProviderStatus.PAID, ProviderStatus.SETTLED, reorder = true)
        deliver(inOrder, ProviderStatus.SETTLED)
        drain(worker("A"))

        for (id in listOf(inOrder, reversed)) {
            assertEquals("SETTLED", tables.payment(id).state)
            assertEquals(listOf("PRESENTED", "ACCEPTED", "PAID", "SETTLED"), tables.partnerStatuses(id))
        }
    }

    @Test
    fun `a contradicting final event after SETTLED is stale and raises an alert`() {
        val id = sent()
        deliver(id, ProviderStatus.SETTLED)
        drain(worker("A"))
        deliver(id, ProviderStatus.REFUSED)
        drain(worker("A"))

        assertEquals("SETTLED", tables.payment(id).state)
        assertEquals("STALE", tables.events(id).last().outcome)
        assertTrue(tables.outbox(id).any { it.first == "ALERT" && it.second.contains("CONTRADICTING_EVENT_AFTER_TERMINAL") })
    }

    @Test
    fun `a reject after PAID means money came back, so MISMATCH for a person and partners not told`() {
        val id = sent()
        deliver(id, ProviderStatus.PAID)
        drain(worker("A"))
        deliver(id, ProviderStatus.REJECTED)
        drain(worker("A"))

        assertEquals("MISMATCH", tables.payment(id).state)
        assertTrue(tables.outbox(id).any { it.first == "ALERT" && it.second.contains("MISMATCH") })
        assertEquals(listOf("PRESENTED", "ACCEPTED", "PAID"), tables.partnerStatuses(id))
    }

    @Test
    fun `an event that contradicts the state parks the payment as MISMATCH with an alert`() {
        val id = sent()
        deliver(id, ProviderStatus.PRESENTED, ProviderStatus.ACCEPTED)
        drain(worker("A"))
        deliver(id, ProviderStatus.REJECTED)
        drain(worker("A"))

        val payment = tables.payment(id)
        assertEquals("MISMATCH", payment.state)
        assertEquals(null, payment.nextActionAt, "waiting on a person: no automatic work")
        assertTrue(tables.outbox(id).any { it.first == "ALERT" && it.second.contains("\"MISMATCH\"") })
        assertEquals(listOf("PRESENTED", "ACCEPTED"), tables.partnerStatuses(id), "MISMATCH is internal")
    }

    @Test
    fun `an event that arrives before the reference is stored is parked, then applied`() {
        // The fake's first reference is REF-1: the event overtakes the send.
        inbox.receive(downstream.eventForUnknownRef("REF-1", ProviderStatus.PRESENTED))
        assertEquals(listOf("PARKED"), tables.inboxStatuses())

        val id = submit("E1")
        drain(worker("A"))

        assertEquals("PRESENTED", tables.payment(id).state)
        assertEquals(listOf("APPLIED"), tables.inboxStatuses())
    }

    // --- Scenario: timeout to UNKNOWN -----------------------------------------------------------

    @Test
    fun `a lost send response is recovered by asking the downstream, then silence leads to UNKNOWN`() {
        downstream.script(SendBehavior.TimeoutAfterProcessing)
        val id = submit("E1")
        drain(worker("A"))

        val sending = tables.payment(id)
        assertEquals("SENDING", sending.state)
        assertEquals("REF-1", sending.downstreamRef)
        assertTrue(tables.events(id).any { it.type == "SEND_TIMEOUT_RECOVERED" })
        assertEquals(1, downstream.processedCount)

        clock.advance(window.plusSeconds(1))
        drain(worker("A"))
        assertUnknown(id)
        assertEquals(1, downstream.sendCallCount, "silence never triggers a blind resend")
    }

    @Test
    fun `a timeout nobody can explain is UNKNOWN, queued for reconciliation, and never turns into failed`() {
        downstream.statusAvailable = false
        downstream.script(SendBehavior.TimeoutBeforeProcessing)
        val id = submit("E1")
        drain(worker("A"))
        // The status query is retried with backoff first: an outage is not yet a reason for a person to look.
        clock.keepRunning(horizon.plusMinutes(1)) { drain(worker("A")) }
        assertUnknown(id)

        val eventsBefore = tables.events(id)
        clock.advance(Duration.ofDays(30))
        drain(worker("A"))
        assertEquals("UNKNOWN", tables.payment(id).state, "unknown does not expire into failed")
        assertEquals(eventsBefore, tables.events(id), "and nothing happens on its own")
        assertEquals(0, tables.count("SELECT count(*) FROM payment_request WHERE state IN ('REJECTED','REFUSED','EXPIRED')"))
    }

    @Test
    fun `silence after PRESENTED leads to UNKNOWN and a late event resolves it`() {
        val id = sent()
        deliver(id, ProviderStatus.PRESENTED)
        drain(worker("A"))
        // Silence: the response window passes and the downstream does not answer the status query either.
        downstream.statusAvailable = false
        clock.advance(window.plusSeconds(1))
        clock.keepRunning(horizon.plusMinutes(1)) { drain(worker("A")) }
        assertUnknown(id)
        downstream.statusAvailable = true

        deliver(id, ProviderStatus.PAID)
        drain(worker("A"))
        assertEquals("PAID", tables.payment(id).state)
        assertEquals(listOf("PRESENTED", "ACCEPTED", "PAID"), tables.partnerStatuses(id))
    }

    private fun assertUnknown(id: UUID) {
        val payment = tables.payment(id)
        assertEquals("UNKNOWN", payment.state)
        assertEquals(null, payment.nextActionAt, "UNKNOWN waits on a person: no due time")
        assertTrue(tables.outbox(id).any { it.first == "RECONCILIATION" })
        assertTrue(tables.partnerStatuses(id).none { it == "UNKNOWN" }, "UNKNOWN is internal")
    }

    // --- Scenario: takeover mid-send ------------------------------------------------------------

    @Test
    fun `a worker that dies after recording SENDING is taken over and resends under the same key`() {
        val id = submit("E1")
        assertFailsWith<SimulatedCrash> { drain(worker("A") { throw SimulatedCrash() }) }
        assertEquals("SENDING", tables.payment(id).state)
        assertEquals(0, downstream.sendCallCount)
        val tokenA = tables.payment(id).leaseToken

        // Lease still held and nothing due yet: nobody else touches it.
        drain(worker("B"))
        assertEquals(tokenA, tables.payment(id).leaseToken)

        clock.advance(window.plusSeconds(1))
        drain(worker("B"))

        assertTrue(tables.payment(id).leaseToken > tokenA)
        assertEquals(listOf(key(id)), downstream.processedKeys())
        assertEquals(1, downstream.sendCallCount, "the downstream saw one send, from the new worker")
        assertEquals("REF-1", tables.payment(id).downstreamRef)
    }

    @Test
    fun `a worker that died after the downstream accepted is taken over without a second send`() {
        val id = submit("E1")
        val dies = worker("A") { p ->
            downstream.send(SendRequest(p.idempotencyKey, p.id, p.amount, p.endToEndId))
            throw SimulatedCrash()
        }
        assertFailsWith<SimulatedCrash> { drain(dies) }

        clock.advance(window.plusSeconds(1))
        drain(worker("B"))

        assertEquals(1, downstream.sendCallCount, "found by status query, not resent")
        assertEquals("REF-1", tables.payment(id).downstreamRef)
        assertTrue(tables.events(id).any { it.type == "STATUS_QUERY" })
    }

    @Test
    fun `a stalled worker that wakes up after a takeover cannot write`() {
        val id = submit("E1")
        val reached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val stalled = worker("A") { reached.countDown(); resume.await(30, TimeUnit.SECONDS) }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val a = pool.submit { drain(stalled) }
            assertTrue(reached.await(30, TimeUnit.SECONDS))

            clock.advance(window.plusSeconds(1))
            drain(worker("B"))
            val afterTakeover = tables.events(id)
            val tokenB = tables.payment(id).leaseToken

            resume.countDown()
            val failure = assertFailsWith<ExecutionException> { a.get(30, TimeUnit.SECONDS) }
            assertIs<LeaseLost>(failure.cause)

            assertEquals(afterTakeover, tables.events(id), "the fenced write changed nothing")
            assertEquals(tokenB, tables.payment(id).leaseToken)
            assertEquals(listOf(key(id)), downstream.processedKeys(), "the stale send was deduplicated by the key")
            assertEquals(2, downstream.sendCallCount)
        } finally {
            resume.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `claiming alone fences the previous holder even if the new holder has not written yet`() {
        // The event sequence would not catch this: nothing has been written since A read the payment.
        val id = submit("E1")
        val a = assertNotNull(store.claim("A"))
        clock.advance(Duration.ofMinutes(2))
        val b = assertNotNull(store.claim("B"))
        assertTrue(b.leaseToken > a.leaseToken)

        val lateWrite = Change(
            type = "VALIDATED", actor = Actor("SERVICE", "A"),
            steps = listOf(Step(State.RECEIVED, State.VALIDATED, Outcome.APPLIED)),
            context = Context(State.VALIDATED), nextActionAt = clock.instant(),
        )
        assertFailsWith<LeaseLost> { store.apply(a, lateWrite) }
        assertEquals("RECEIVED", tables.payment(id).state)
        assertEquals(1, tables.events(id).size)
        // The new holder's own write goes through.
        store.apply(b, lateWrite.copy(actor = Actor("SERVICE", "B")))
        assertEquals("VALIDATED", tables.payment(id).state)
    }

    // --- Scenario: amount invariants ------------------------------------------------------------

    @Test
    fun `amounts that are not exact positive minor units are rejected at the door`() {
        val bad = listOf("12.345" to "EUR", "0" to "EUR", "-1.00" to "EUR", "1e3" to "EUR", "abc" to "EUR", "0.5" to "JPY", "1" to "ZZZ")
        val results = ingestion.submit("P1", "M1", bad.mapIndexed { i, (amount, currency) -> Instruction("E$i", amount, currency) })
        assertTrue(results.all { it is InstructionResult.Rejected }, results.toString())
        assertEquals(0, tables.count("SELECT count(*) FROM payment_request"))
    }

    @Test
    fun `the amount reaches the downstream exactly as stored and never changes`() {
        val id = submit("E1", "12.34", "EUR")
        drain(worker("A"))
        assertEquals(Money(1234, "EUR"), downstream.requestFor(key(id))?.amount)
        deliver(id, ProviderStatus.PAID)
        drain(worker("A"))
        val paid = tables.payment(id)
        assertEquals("PAID", paid.state)
        assertEquals(1234, paid.amountMinor)
        assertEquals("EUR", paid.currency)
    }

    @Test
    fun `totals per currency are conserved from ingestion to the downstream`() {
        val currencies = listOf("EUR" to 2, "JPY" to 0, "KWD" to 3)
        val expected = HashMap<String, Long>()
        val instructions = (1..12).map { i ->
            val (currency, exponent) = currencies[i % 3]
            val minor = 1000L + i * 7
            expected.merge(currency, minor, Long::plus)
            val amount = if (exponent == 0) "$minor" else {
                val unit = Math.pow(10.0, exponent.toDouble()).toLong()
                "${minor / unit}.${(minor % unit).toString().padStart(exponent, '0')}"
            }
            Instruction("E$i", amount, currency)
        }
        ingestion.submit("P1", "M1", instructions).forEach { assertIs<InstructionResult.Accepted>(it) }
        drain(worker("A"))

        for ((currency, total) in expected) {
            assertEquals(total, tables.count("SELECT sum(amount_minor) FROM payment_request WHERE currency = '$currency'"), currency)
            val atDownstream = downstream.processedKeys().sumOf { k ->
                downstream.requestFor(k)!!.amount.takeIf { it.currency == currency }?.minor ?: 0L
            }
            assertEquals(total, atDownstream, "$currency at the downstream")
        }
        assertEquals(12, downstream.processedCount)
    }

    // --- Several workers ------------------------------------------------------------------------

    @Test
    fun `several workers never double-process a payment and leave a gap-free history`() {
        val ids = (1..40).map { submit("E$it", "10.00") }
        val pool = Executors.newFixedThreadPool(4)
        try {
            runConcurrently(pool, "W")
            ids.forEach { id ->
                val events = tables.events(id)
                assertEquals((1..tables.payment(id).eventSeq).toList(), events.map { it.seq }, "gap-free, ordered history")
                assertEquals(1, events.count { it.type == "SENDING" }, "sent once")
                assertEquals("SENDING", tables.payment(id).state)
            }
            assertEquals(40, downstream.sendCallCount)
            assertEquals(40, downstream.processedCount)
            assertEquals(40, tables.count("SELECT count(DISTINCT downstream_ref) FROM payment_request"))

            ids.forEach { deliver(it, ProviderStatus.PRESENTED, ProviderStatus.ACCEPTED, ProviderStatus.PAID) }
            runConcurrently(pool, "V")
            ids.forEach { id ->
                assertEquals("PAID", tables.payment(id).state)
                assertEquals(listOf("PRESENTED", "ACCEPTED", "PAID"), tables.partnerStatuses(id))
                assertEquals((1..tables.payment(id).eventSeq).toList(), tables.events(id).map { it.seq })
            }
        } finally {
            pool.shutdownNow()
        }
    }

    private fun runConcurrently(pool: java.util.concurrent.ExecutorService, prefix: String) {
        val tasks = (1..4).map { n ->
            pool.submit {
                val w = worker("$prefix$n")
                var idle = 0
                while (idle < 5) {
                    if (w.runOnce()) idle = 0 else { idle++; Thread.sleep(5) }
                }
            }
        }
        tasks.forEach { it.get(120, TimeUnit.SECONDS) }
        drain(worker("final-$prefix"))
    }

    // --- Cancellation ---------------------------------------------------------------------------

    @Test
    fun `a cancellation before the send cancels without ever sending`() {
        val id = submit("E1")
        tables.requestCancellation(id)
        drain(worker("A"))
        assertEquals("CANCELLED", tables.payment(id).state)
        assertEquals(0, downstream.sendCallCount)
        assertEquals(listOf("CANCELLED"), tables.partnerStatuses(id))
    }

    @Test
    fun `a cancellation in flight is forwarded and confirmed by the downstream`() {
        val id = sent()
        tables.requestCancellation(id)
        drain(worker("A"))
        assertEquals("CANCELLATION_REQUESTED", tables.payment(id).state)
        assertTrue(tables.outbox(id).any { it.first == "FORWARD_CANCELLATION" })

        deliver(id, ProviderStatus.CANCELLED)
        drain(worker("A"))
        assertEquals("CANCELLED", tables.payment(id).state)
        assertEquals(listOf("CANCELLATION_REQUESTED", "CANCELLED"), tables.partnerStatuses(id))
    }

    // --- The scenario list is itself checked ----------------------------------------------------

    @Test
    fun `none of the scenario groups can be silently dropped`() {
        val names = ScenarioTests::class.java.declaredMethods
            .filter { it.isAnnotationPresent(org.junit.jupiter.api.Test::class.java) }
            .map { it.name }
        listOf("duplicate", "out-of-order", "timeout", "takeover", "dies", "stalled", "amount", "several workers")
            .forEach { topic -> assertTrue(names.any { it.contains(topic) }, "no scenario test covers '$topic'") }
        assertTrue(names.size >= 20, "scenario count dropped to ${names.size}")
    }
}
