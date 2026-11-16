package engine

import java.time.Duration
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Downstream outages. An unreachable downstream is not an unknown outcome: the worker backs off and retries on its
 * own, and only a payment whose outage outlasts the horizon goes to a person.
 */
class OutageTests {
    private val clock = MutableClock()
    private val db = TestPostgres.db
    private val tables = Tables(db)
    private val config = WorkerConfig("A")
    private val store = Store(db, clock, Duration.ofMinutes(1))
    private val ingestion = Ingestion(db, clock)
    private val inbox = ProviderInbox(db, clock)
    private val downstream = FakeDownstream()
    private val worker = Worker(store, downstream, clock, config)

    @BeforeTest
    fun reset() {
        TestPostgres.reset()
        tables.addPartner("P1")
    }

    private fun drain() {
        while (worker.runOnce()) { /* until nothing is due */ }
    }

    private fun submit(e2e: String): UUID =
        assertIs<InstructionResult.Accepted>(
            ingestion.submit("P1", "M-$e2e", listOf(Instruction(e2e, "12.34", "EUR"))).single(),
        ).paymentId

    private fun key(id: UUID) = "$id:1"

    private fun downstreamUp(up: Boolean) {
        downstream.sendAvailable = up
        downstream.statusAvailable = up
    }

    private fun failedCalls(id: UUID) = tables.events(id).count { it.type in setOf("STATUS_UNAVAILABLE", "SEND_FAILED") }

    private fun state(id: UUID) = tables.payment(id).state

    @Test
    fun `a downstream outage shorter than the horizon resolves every payment without a person`() {
        // Payments waiting on the debtor. The downstream pays them during the outage, and the webhooks are lost.
        val waiting = (1..5).map { submit("W$it") }
        drain()
        waiting.forEach { inbox.receive(downstream.event(key(it), ProviderStatus.PRESENTED)) }
        drain()
        waiting.forEach { downstream.event(key(it), ProviderStatus.PAID) }

        // The outage starts as a send is answered: the downstream has the payment, we never learn its reference.
        downstream.statusAvailable = false
        downstream.script(SendBehavior.TimeoutAfterProcessing)
        val lostResponse = submit("L1")
        drain()
        downstreamUp(false)

        // Payments submitted during the outage: every send fails.
        val duringOutage = (1..5).map { submit("N$it") }
        clock.keepRunning(Duration.ofMinutes(30)) { drain() }

        // The downstream is back and works normally: it presents each payment it receives and says so.
        downstreamUp(true)
        val presented = waiting.map { key(it) }.toMutableSet()
        clock.keepRunning(config.maxRetryDelay.plusMinutes(1)) {
            downstream.processedKeys().filter { presented.add(it) }
                .forEach { inbox.receive(downstream.event(it, ProviderStatus.PRESENTED)) }
            drain()
        }

        val all = waiting + lostResponse + duringOutage
        assertEquals(emptyList(), all.filter { state(it) == "UNKNOWN" }, "no payment needs a person")
        waiting.forEach { assertEquals("PAID", state(it), "the lost webhook is recovered by asking") }
        (duringOutage + lostResponse).forEach { assertEquals("PRESENTED", state(it)) }
        assertEquals(all.size, downstream.processedCount, "each payment reached the downstream once")
        all.forEach { assertTrue(failedCalls(it) <= 8, "backed off: ${failedCalls(it)} failed calls in a 30-minute outage") }
    }

    @Test
    fun `an outage that outlasts the horizon hands the payment to a person, and not before`() {
        downstreamUp(false)
        val id = submit("E1")
        drain()
        clock.keepRunning(config.outageHorizon.minusMinutes(1)) { drain() }
        assertEquals("SENDING", state(id), "still inside the horizon")

        clock.keepRunning(Duration.ofMinutes(2)) { drain() }
        assertEquals("UNKNOWN", state(id))
        assertTrue(tables.outbox(id).any { it.first == "RECONCILIATION" })
        assertEquals(0, downstream.processedCount)
    }

    @Test
    fun `a status answer ends the outage, even when it reports nothing new`() {
        val id = submit("B1")
        drain()
        inbox.receive(downstream.event(key(id), ProviderStatus.PRESENTED))
        drain()

        // A short blip, then three healthy hours in which every status answer says what we already know.
        downstream.statusAvailable = false
        clock.advance(config.responseWindow)
        drain()
        downstream.statusAvailable = true
        clock.keepRunning(Duration.ofHours(3)) { drain() }

        // A second short outage is a new outage, not the continuation of the first.
        val secondOutage = clock.now
        downstream.statusAvailable = false
        clock.keepRunning(Duration.ofMinutes(6)) { drain() }

        assertEquals("PRESENTED", state(id))
        val streak = assertNotNull(store.currentStreak(id, setOf("STATUS_UNAVAILABLE", "SEND_FAILED")))
        assertTrue(!streak.startedAt.isBefore(secondOutage), "the outage started at ${streak.startedAt}")
    }

    @Test
    fun `repeated webhooks during a status outage do not restart the horizon`() {
        val id = submit("E1")
        drain()
        inbox.receive(downstream.event(key(id), ProviderStatus.PRESENTED))
        drain()
        downstream.statusAvailable = false
        clock.advance(config.responseWindow)
        // The downstream repeats what we already know every half hour; it says nothing new.
        repeat(5) {
            inbox.receive(downstream.event(key(id), ProviderStatus.PRESENTED))
            clock.keepRunning(Duration.ofMinutes(30)) { drain() }
        }
        assertEquals("UNKNOWN", state(id), "a stale event is no answer to the status query")
    }

    @Test
    fun `a send endpoint that stays down backs off and goes to a person at the horizon`() {
        downstream.sendAvailable = false
        val id = submit("E1")
        drain()
        clock.keepRunning(config.outageHorizon.minusMinutes(1)) { drain() }
        assertEquals("SENDING", state(id))
        assertTrue(failedCalls(id) <= 20, "backed off: ${failedCalls(id)} failed sends in two hours")

        clock.keepRunning(Duration.ofMinutes(2)) { drain() }
        assertEquals("UNKNOWN", state(id))
    }

    @Test
    fun `a send that fails while status queries work is resent under the same key, never UNKNOWN`() {
        downstream.sendAvailable = false
        val id = submit("E1")
        drain()
        assertEquals("SENDING", state(id), "the downstream says it has nothing: the send simply failed")
        assertTrue(tables.events(id).any { it.type == "SEND_FAILED" })
        assertEquals(
            1,
            tables.count("SELECT count(*) FROM payment_event WHERE type = 'SEND_FAILED' AND payload->>'error' = 'DownstreamTimeout'"),
            "each failed send keeps what went wrong",
        )

        downstream.sendAvailable = true
        clock.keepRunning(Duration.ofMinutes(2)) { drain() }
        assertEquals("SENDING", state(id))
        assertNotNull(tables.payment(id).downstreamRef)
        assertEquals(listOf(key(id)), downstream.processedKeys(), "the resend used the first attempt's key")
    }
}
