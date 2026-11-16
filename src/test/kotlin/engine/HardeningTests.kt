package engine

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Races and awkward inputs found by an independent check of the worker core. Each one failed before its fix. */
class HardeningTests {
    private val clock = MutableClock()
    private val db = TestPostgres.db
    private val tables = Tables(db)
    private val window = Duration.ofMinutes(5)
    private val retryDelay = Duration.ofSeconds(30)
    private val horizon = WorkerConfig("any").outageHorizon
    private val store = Store(db, clock, Duration.ofMinutes(1))
    private val ingestion = Ingestion(db, clock)
    private val inbox = ProviderInbox(db, clock)
    private val downstream = FakeDownstream()

    @BeforeTest
    fun reset() {
        TestPostgres.reset()
        tables.addPartner("P1")
    }

    private fun worker(id: String, s: Store = store, hook: (Payment) -> Unit = {}) =
        Worker(s, downstream, clock, WorkerConfig(id, window), hook)

    private fun drain(w: Worker) {
        while (w.runOnce()) { /* until nothing is due */ }
    }

    private fun submit(e2e: String): UUID =
        assertIs<InstructionResult.Accepted>(
            ingestion.submit("P1", "M-$e2e", listOf(Instruction(e2e, "12.34", "EUR"))).single(),
        ).paymentId

    private fun key(id: UUID) = "$id:1"

    /** A Db whose transaction that relinks parked events pauses just before it commits. */
    private class PausingDb(real: Db) {
        val atCommit = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val db: Db

        init {
            // Reaching into Db is acceptable here: the test needs to hold one transaction open at its commit.
            val field = Db::class.java.getDeclaredField("dataSource").apply { isAccessible = true }
            val source = field.get(real) as DataSource
            val wrapped = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, m, args ->
                val result = call(m, source, args)
                if (m.name == "getConnection") wrap(result as Connection) else result
            } as DataSource
            db = Db(wrapped)
        }

        private fun call(m: java.lang.reflect.Method, target: Any, args: Array<Any?>?): Any? =
            try { m.invoke(target, *(args ?: emptyArray())) } catch (e: InvocationTargetException) { throw e.targetException }

        private fun wrap(c: Connection): Connection {
            var relinked = false
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, m, args ->
                if (m.name == "prepareStatement" && (args?.get(0) as? String)?.contains("status = 'PARKED' AND provider") == true) relinked = true
                if (m.name == "commit" && relinked) {
                    atCommit.countDown()
                    resume.await(30, TimeUnit.SECONDS)
                }
                call(m, c, args)
            } as Connection
        }
    }

    @Test
    fun `an event received while the reference is being stored is never left parked`() {
        val pausing = PausingDb(db)
        val id = submit("E1")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val sender = pool.submit { drain(worker("A", Store(pausing.db, clock, Duration.ofMinutes(1)))) }
            assertTrue(pausing.atCommit.await(30, TimeUnit.SECONDS), "the reference-storing transaction never reached commit")
            // The downstream reports PRESENTED right away. The receive either waits for that transaction or links itself.
            val receiver = pool.submit<Boolean> { inbox.receive(downstream.event(key(id), ProviderStatus.PRESENTED)) }
            Thread.sleep(300)
            pausing.resume.countDown()
            assertTrue(receiver.get(30, TimeUnit.SECONDS))
            sender.get(30, TimeUnit.SECONDS)

            drain(worker("B"))
            assertEquals(listOf("APPLIED"), tables.inboxStatuses(), "event must not be left PARKED")
            assertEquals("PRESENTED", tables.payment(id).state)
        } finally {
            pausing.resume.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `a takeover caused by a cancellation does not lose the reference the previous holder was given`() {
        val id = submit("E1")
        val reached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val stalled = worker("A") { reached.countDown(); resume.await(30, TimeUnit.SECONDS) }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val a = pool.submit { drain(stalled) }
            assertTrue(reached.await(30, TimeUnit.SECONDS))
            clock.advance(Duration.ofMinutes(2)) // the lease (1 minute) expired, the response window (5) did not
            tables.requestCancellation(id)
            drain(worker("B"))
            assertEquals("CANCELLATION_REQUESTED", tables.payment(id).state)

            resume.countDown()
            val failure = assertFailsWith<ExecutionException> { a.get(30, TimeUnit.SECONDS) }
            assertIs<LeaseLost>(failure.cause)
            assertEquals(listOf(key(id)), downstream.processedKeys(), "the downstream did accept it")
            assertEquals(null, tables.payment(id).downstreamRef, "the late holder could not record the reference")

            // The downstream reports on the payment. Silence must not be taken for "nothing to wait for".
            inbox.receive(downstream.event(key(id), ProviderStatus.PRESENTED))
            clock.advance(window.plusSeconds(1))
            drain(worker("C"))

            assertEquals("REF-1", tables.payment(id).downstreamRef)
            assertTrue(tables.events(id).any { it.type == "STATUS_QUERY" })
            assertEquals(listOf("APPLIED"), tables.inboxStatuses())
            assertEquals("CANCELLATION_REQUESTED", tables.payment(id).state, "still waiting for the cancellation answer")
        } finally {
            resume.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun `the same instructions resent concurrently in another order deduplicate instead of deadlocking`() {
        val failures = mutableListOf<String>()
        repeat(3) { round ->
            val ids = (1..100).map { "R$round-E$it" }
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val calls = listOf(ids, ids.reversed()).mapIndexed { i, list ->
                    pool.submit<List<InstructionResult>> {
                        start.await()
                        ingestion.submit("P1", "M-$round-$i", list.map { Instruction(it, "1.00", "EUR") })
                    }
                }
                start.countDown()
                val results = calls.mapNotNull {
                    try { it.get(60, TimeUnit.SECONDS) } catch (e: ExecutionException) {
                        failures += "${e.cause?.javaClass?.simpleName}"
                        null
                    }
                }
                // Every instruction was accepted exactly once between the two submissions.
                assertEquals(100, results.flatten().count { it is InstructionResult.Accepted })
                assertEquals(100, results.flatten().count { it is InstructionResult.Duplicate })
            } finally {
                pool.shutdownNow()
            }
        }
        assertEquals(emptyList(), failures)
        assertEquals(300, tables.count("SELECT count(*) FROM payment_request"))
    }

    @Test
    fun `results come back in the order the instructions were submitted`() {
        val results = ingestion.submit(
            "P1", "M1", listOf(Instruction("C", "1.00", "EUR"), Instruction("A", "1.00", "EUR"), Instruction("B", "abc", "EUR")),
        )
        assertIs<InstructionResult.Accepted>(results[0])
        assertIs<InstructionResult.Accepted>(results[1])
        assertIs<InstructionResult.Rejected>(results[2])
        assertEquals(2, tables.count("SELECT count(*) FROM payment_request WHERE end_to_end_id IN ('A', 'C')"))
        assertEquals(0, tables.count("SELECT count(*) FROM payment_request WHERE end_to_end_id = 'B'"))
    }

    @Test
    fun `a downstream reason code with quotes is recorded safely`() {
        downstream.script(SendBehavior.Reject("AC\"01\\x"))
        val id = submit("E1")
        drain(worker("A"))
        assertEquals("REJECTED", tables.payment(id).state)
        assertTrue(tables.events(id).any { it.type == "SEND_REJECTED" })
        assertEquals(1, tables.count("SELECT count(*) FROM payment_event WHERE payload->>'reasonCode' = 'AC\"01\\x'"))
    }

    @Test
    fun `a reason code the database cannot store is cleaned and bounded, never stuck in SENDING`() {
        downstream.script(SendBehavior.Reject("AB\u0000CD" + "x".repeat(500)))
        val id = submit("E1")
        drain(worker("A"))
        assertEquals("REJECTED", tables.payment(id).state)
        val stored = tables.count("SELECT max(length(payload->>'reasonCode')) FROM payment_event WHERE type = 'SEND_REJECTED'")
        assertTrue(stored in 1..64, "reason code stored with $stored characters")
    }

    @Test
    fun `a late event for a payment waiting on a person is not starved by newer due work`() {
        val id = submit("E1")
        drain(worker("A"))
        inbox.receive(downstream.event(key(id), ProviderStatus.PRESENTED))
        drain(worker("A"))
        downstream.statusAvailable = false
        clock.advance(window.plusSeconds(1))
        clock.keepRunning(horizon.plusMinutes(1)) { drain(worker("A")) }
        assertEquals("UNKNOWN", tables.payment(id).state)
        downstream.statusAvailable = true

        val newer = submit("E2")
        inbox.receive(downstream.event(key(id), ProviderStatus.PAID))
        assertTrue(worker("B").runOnce())
        assertEquals("PAID", tables.payment(id).state, "the first claim goes to the older payment that has an event")
        assertEquals("RECEIVED", tables.payment(newer).state)
    }

    @Test
    fun `an unavailable status answer is retried, and the reference is learned once the downstream answers`() {
        downstream.statusAvailable = false
        downstream.script(SendBehavior.TimeoutAfterProcessing)
        val id = submit("E1")
        drain(worker("A"))
        assertEquals("SENDING", tables.payment(id).state, "one failed query is not a reason to wait for a person")
        assertTrue(tables.events(id).any { it.type == "STATUS_UNAVAILABLE" })

        // The downstream comes back: the reference is learned and there is no second send.
        downstream.statusAvailable = true
        clock.advance(retryDelay.plusSeconds(1))
        drain(worker("A"))
        assertEquals("REF-1", tables.payment(id).downstreamRef)
        assertEquals(1, downstream.sendCallCount)
        assertEquals("SENDING", tables.payment(id).state)
    }

    @Test
    fun `reference bookkeeping is recorded as technical, not as a business event`() {
        downstream.script(SendBehavior.Normal, SendBehavior.TimeoutAfterProcessing)
        submit("E1")
        submit("E2")
        drain(worker("A"))
        assertEquals(
            0,
            tables.count(
                "SELECT count(*) FROM payment_event WHERE from_state = to_state AND category = 'BUSINESS' " +
                    "AND type IN ('SEND_RESPONSE', 'SEND_TIMEOUT_RECOVERED', 'STATUS_QUERY')",
            ),
        )
        assertEquals(2, tables.count("SELECT count(*) FROM payment_event WHERE type IN ('SEND_RESPONSE', 'SEND_TIMEOUT_RECOVERED')"))
    }

    @Test
    fun `a waiting payment that gets a final event is claimed before newer due work`() {
        val id = submit("E1")
        drain(worker("A"))
        inbox.receive(downstream.event(key(id), ProviderStatus.PRESENTED))
        drain(worker("A"))
        assertEquals("PRESENTED", tables.payment(id).state, "waiting on the downstream, due in the future")

        val newer = submit("E2")
        inbox.receive(downstream.event(key(id), ProviderStatus.PAID))
        assertTrue(worker("B").runOnce())
        assertEquals("PAID", tables.payment(id).state, "an unapplied event is claimed first, whatever the due time")
        assertEquals("RECEIVED", tables.payment(newer).state)
    }

    @Test
    fun `outages are measured per outage, not over the whole life of the payment`() {
        val outage = setOf("STATUS_UNAVAILABLE", "SEND_FAILED")
        submit("E1")
        var p = assertNotNull(store.claim("A"))
        fun failed(type: String) {
            p = store.apply(
                p,
                Change(type, Actor("DOWNSTREAM", "fake"), listOf(Step(p.state, p.state, Outcome.STALE)), p.context, clock.instant()),
            )
        }
        val firstStart = clock.instant()
        failed("STATUS_UNAVAILABLE")
        clock.advance(Duration.ofMinutes(1))
        failed("SEND_FAILED")
        assertEquals(Streak(2, firstStart), store.currentStreak(p.id, outage), "both kinds of failure are one outage")
        // Something else happens, so the outage is over.
        p = store.apply(
            p,
            Change("VALIDATED", Actor("SERVICE", "A"), listOf(Step(State.RECEIVED, State.VALIDATED, Outcome.APPLIED)), Context(State.VALIDATED), clock.instant()),
        )
        assertEquals(null, store.currentStreak(p.id, outage))
        clock.advance(Duration.ofMinutes(1))
        failed("STATUS_UNAVAILABLE")
        assertEquals(Streak(1, clock.instant()), store.currentStreak(p.id, outage))
    }

    @Test
    fun `a failed rollback does not hide the error that caused it`() {
        val connection = Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, m, _ ->
            if (m.name == "rollback") throw java.sql.SQLException("connection is gone")
            null
        } as Connection
        val source = Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, _, _ -> connection } as DataSource

        val failure = assertFailsWith<IllegalStateException> { Db(source).tx { error("the real failure") } }
        assertEquals("the real failure", failure.message)
        assertTrue(failure.suppressed.any { it is java.sql.SQLException })
    }

    @Test
    fun `a resend that differs only in an absent versus an empty reference, or in field boundaries, is a conflict`() {
        val first = assertIs<InstructionResult.Accepted>(
            ingestion.submit("P1", "M1", listOf(Instruction("E1", "1.00", "EUR", debtorReference = "a\u001fb", creditorReference = "c"))).single(),
        )
        // Same characters, split differently across the fields.
        assertEquals(
            InstructionResult.Conflict(first.paymentId),
            ingestion.submit("P1", "M2", listOf(Instruction("E1", "1.00", "EUR", debtorReference = "a", creditorReference = "b\u001fc"))).single(),
        )
        val second = assertIs<InstructionResult.Accepted>(
            ingestion.submit("P1", "M3", listOf(Instruction("E2", "1.00", "EUR", debtorReference = null))).single(),
        )
        assertEquals(
            InstructionResult.Conflict(second.paymentId),
            ingestion.submit("P1", "M4", listOf(Instruction("E2", "1.00", "EUR", debtorReference = ""))).single(),
        )
    }

    @Test
    fun `no blind resend when the downstream has lost a payment it once accepted`() {
        val id = submit("E1")
        drain(worker("A"))
        assertEquals("REF-1", tables.payment(id).downstreamRef)
        downstream.forget(key(id))
        clock.advance(window.plusSeconds(1))
        drain(worker("A"))
        assertEquals("UNKNOWN", tables.payment(id).state, "an accepted payment the downstream cannot find is for a person")
        assertEquals(1, downstream.sendCallCount)
        assertEquals("REF-1", tables.payment(id).downstreamRef, "the first reference is never replaced")
    }

    @Test
    fun `a reference the database cannot store, or that is already taken, ends in UNKNOWN instead of retrying forever`() {
        downstream.script(SendBehavior.AcceptWithRef("A\u0000B"))
        val odd = submit("E1")
        drain(worker("A"))
        assertEquals("UNKNOWN", tables.payment(odd).state)
        assertTrue(tables.events(odd).any { it.type == "UNUSABLE_REFERENCE" })
        assertTrue(tables.outbox(odd).any { it.first == "RECONCILIATION" })

        downstream.script(SendBehavior.AcceptWithRef("DUP"), SendBehavior.AcceptWithRef("DUP"))
        val a = submit("E2")
        val b = submit("E3")
        drain(worker("A"))
        assertEquals(setOf("SENDING", "UNKNOWN"), setOf(tables.payment(a).state, tables.payment(b).state))
    }

    @Test
    fun `a status call that throws is treated as unavailable, then UNKNOWN once the outage outlasts the horizon`() {
        downstream.statusThrows = true
        downstream.script(SendBehavior.TimeoutAfterProcessing)
        val id = submit("E1")
        drain(worker("A"))
        assertEquals("SENDING", tables.payment(id).state)
        assertTrue(tables.events(id).any { it.type == "STATUS_UNAVAILABLE" })
        clock.keepRunning(horizon.plusMinutes(1)) { drain(worker("A")) }
        assertEquals("UNKNOWN", tables.payment(id).state)
    }

    @Test
    fun `a takeover that is overdue checks the downstream before it applies a cancellation`() {
        val id = submit("E1")
        val reached = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val stalled = worker("A") { reached.countDown(); resume.await(30, TimeUnit.SECONDS) }
        val pool = Executors.newSingleThreadExecutor()
        try {
            val a = pool.submit { drain(stalled) }
            assertTrue(reached.await(30, TimeUnit.SECONDS))
            // The downstream received the request that worker A made, then A died before recording the answer.
            downstream.send(SendRequest(key(id), id, Money(1234, "EUR"), "E1"))
            clock.advance(window.plusMinutes(1)) // past both the lease and the response window
            tables.requestCancellation(id)
            assertTrue(worker("B").runOnce())
            assertEquals("REF-1", tables.payment(id).downstreamRef, "the overdue check ran first and learned the reference")
            assertEquals("CANCELLATION_REQUESTED", tables.payment(id).state)
            resume.countDown()
            assertFailsWith<ExecutionException> { a.get(30, TimeUnit.SECONDS) }
        } finally {
            resume.countDown()
            pool.shutdownNow()
        }
    }

    /** A payment that reached ACCEPTED with the downstream's PAID webhook lost. */
    private fun acceptedWithLostPaid(): UUID {
        val id = submit("E1")
        drain(worker("A"))
        inbox.receive(downstream.event(key(id), ProviderStatus.PRESENTED))
        drain(worker("A"))
        inbox.receive(downstream.event(key(id), ProviderStatus.ACCEPTED))
        drain(worker("A"))
        assertEquals("ACCEPTED", tables.payment(id).state)
        downstream.event(key(id), ProviderStatus.PAID) // the downstream moved on; the webhook never arrives
        return id
    }

    @Test
    fun `when the response window passes the downstream is asked, and a lost PAID webhook is recovered`() {
        val id = acceptedWithLostPaid()
        clock.advance(window.plusSeconds(1))
        drain(worker("A"))
        assertEquals("PAID", tables.payment(id).state)
        assertTrue(tables.outbox(id).none { it.first == "RECONCILIATION" })
    }

    @Test
    fun `a downstream that still reports the same status keeps the payment waiting, not UNKNOWN`() {
        val id = submit("E1")
        drain(worker("A"))
        inbox.receive(downstream.event(key(id), ProviderStatus.PRESENTED))
        drain(worker("A"))
        repeat(3) {
            clock.advance(window.plusSeconds(1))
            drain(worker("A"))
            assertEquals("PRESENTED", tables.payment(id).state)
            assertTrue(tables.payment(id).nextActionAt!!.isAfter(clock.instant()), "the window was re-armed")
        }
    }

    @Test
    fun `a write based on a stale read is fenced by the event sequence`() {
        submit("E1")
        val claimed = assertNotNull(store.claim("A"))
        val change = Change(
            type = "VALIDATED", actor = Actor("SERVICE", "A"),
            steps = listOf(Step(State.RECEIVED, State.VALIDATED, Outcome.APPLIED)),
            context = Context(State.VALIDATED), nextActionAt = clock.instant(),
        )
        store.apply(claimed, change)
        // Same lease token, but the payment moved on since `claimed` was read: the sequence must fence it, because
        // the event table's own key does not stop a repeated sequence number.
        assertFailsWith<LeaseLost> { store.apply(claimed, change) }
        assertEquals(2, tables.events(claimed.id).size)
    }
}
