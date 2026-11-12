package engine

import engine.Input.Advance
import engine.Input.CancelRequested
import engine.Input.ProviderReported
import engine.Input.ResponseTimeout
import engine.ProviderStatus.ACCEPTED as ProviderAccepted
import engine.State.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StateMachineTest {
    private fun transition(ctx: Context, input: Input) = PaymentStateMachine.transition(ctx, input)

    private fun Context.then(input: Input): Transition.Valid =
        assertIs<Transition.Valid>(transition(this, input), "$input after $this")

    /** Applies the inputs one after the other from [from]; each must be valid. */
    private fun run(vararg inputs: Input, from: Context = Context.initial): Context =
        inputs.fold(from) { ctx, input -> ctx.then(input).context }

    private fun reported(status: ProviderStatus) = ProviderReported(status)

    private fun Transition.Valid.outcomes() = steps.map { it.outcome }

    /** Partner statuses emitted by running [inputs] from [from], in order. */
    private fun partnerStatuses(vararg inputs: Input, from: Context): List<State> {
        var ctx = from
        val seen = mutableListOf<State>()
        for (input in inputs) {
            val result = ctx.then(input)
            seen += result.steps.flatMap { it.effects }.filterIsInstance<Effect.PartnerStatus>().map { it.state }
            ctx = result.context
        }
        return seen
    }

    private val ready = run(Advance(VALIDATED), Advance(PENDING_SCREENING), Advance(READY_TO_SEND))
    private val sending = run(Advance(SENDING), from = ready)
    private val presented = run(reported(ProviderStatus.PRESENTED), from = sending)
    private val accepted = run(reported(ProviderAccepted), from = presented)

    private val unsent = listOf(
        Context.initial,
        run(Advance(VALIDATED)),
        run(Advance(VALIDATED), Advance(PENDING_SCREENING)),
        ready,
    )

    private val terminals: Map<State, Context> = mapOf(
        PAID to run(reported(ProviderStatus.PAID), from = sending),
        REJECTED to run(reported(ProviderStatus.REJECTED), from = sending),
        REFUSED to run(reported(ProviderStatus.REFUSED), from = presented),
        EXPIRED to run(reported(ProviderStatus.EXPIRED), from = presented),
        CANCELLED to run(CancelRequested),
    )

    private val mismatch = run(reported(ProviderStatus.PAID), from = ready)

    private val allInputs: List<Input> =
        State.entries.map { Advance(it) } +
            ProviderStatus.entries.map { ProviderReported(it) } +
            listOf(CancelRequested, ResponseTimeout)

    // --- Worker steps -------------------------------------------------------------------------

    @Test
    fun `worker walks the main path to SENDING one step at a time and records the position`() {
        assertEquals(State.RECEIVED, Context.initial.state)
        assertEquals(null, ready.position)
        assertEquals(SENDING, sending.state)
        assertEquals(SENDING, sending.position)
    }

    @Test
    fun `worker can reject before sending`() {
        for (from in listOf(unsent[0], unsent[1], unsent[2])) {
            assertEquals(REJECTED, from.then(Advance(REJECTED)).state)
        }
    }

    @Test
    fun `worker cannot skip a state or enter a downstream state`() {
        assertIs<Transition.Invalid>(transition(Context.initial, Advance(READY_TO_SEND)))
        assertIs<Transition.Invalid>(transition(unsent[1], Advance(SENDING)))
        assertIs<Transition.Invalid>(transition(sending, Advance(PRESENTED)))
        assertIs<Transition.Invalid>(transition(presented, Advance(PAID)))
        assertIs<Transition.Invalid>(transition(ready, Advance(REJECTED)))
    }

    @Test
    fun `worker cannot advance a terminal payment`() {
        for ((terminal, ctx) in terminals) {
            for (to in State.entries) {
                assertIs<Transition.Invalid>(transition(ctx, Advance(to)), "$terminal -> $to")
            }
        }
    }

    // --- Provider events ----------------------------------------------------------------------

    @Test
    fun `provider event for the next state is applied`() {
        val result = sending.then(reported(ProviderStatus.PRESENTED))
        assertEquals(PRESENTED, result.state)
        assertEquals(PRESENTED, result.context.position)
        assertEquals(listOf(Outcome.APPLIED), result.outcomes())
    }

    @Test
    fun `provider event ahead of the current state infers the skipped states and still emits their effects`() {
        val result = sending.then(reported(ProviderStatus.PAID))
        assertEquals(PAID, result.state)
        assertEquals(listOf(PRESENTED, ACCEPTED, PAID), result.steps.map { it.to })
        assertEquals(listOf(Outcome.INFERRED, Outcome.INFERRED, Outcome.APPLIED), result.outcomes())
        assertEquals(
            listOf(PRESENTED, ACCEPTED, PAID).map { listOf<Effect>(Effect.PartnerStatus(it)) },
            result.steps.map { it.effects },
        )
    }

    @Test
    fun `refusal from SENDING infers PRESENTED`() {
        val result = sending.then(reported(ProviderStatus.REFUSED))
        assertEquals(listOf(PRESENTED, REFUSED), result.steps.map { it.to })
        assertEquals(listOf(Outcome.INFERRED, Outcome.APPLIED), result.outcomes())
    }

    @Test
    fun `provider event behind the current state is stale and changes nothing`() {
        val result = accepted.then(reported(ProviderStatus.PRESENTED))
        assertEquals(accepted, result.context)
        assertEquals(listOf(Outcome.STALE), result.outcomes())
        assertTrue(result.steps.single().effects.isEmpty())
    }

    @Test
    fun `duplicate provider event is stale`() {
        val result = presented.then(reported(ProviderStatus.PRESENTED))
        assertEquals(presented, result.context)
        assertEquals(listOf(Outcome.STALE), result.outcomes())
    }

    @Test
    fun `provider event on a terminal payment is stale, and alerted only if it contradicts the outcome`() {
        for ((terminal, ctx) in terminals) {
            for (status in ProviderStatus.entries) {
                val result = ctx.then(reported(status))
                assertEquals(ctx, result.context, "$terminal after $status")
                assertEquals(listOf(Outcome.STALE), result.outcomes())
                val contradicts = status.reports.terminal && status.reports != terminal
                val expected = if (contradicts) {
                    listOf<Effect>(Effect.RaiseAlert(AlertReason.CONTRADICTING_EVENT_AFTER_TERMINAL))
                } else {
                    emptyList()
                }
                assertEquals(expected, result.steps.single().effects, "$terminal after $status")
            }
        }
    }

    @Test
    fun `money moving after a cancellation is recorded as stale and alerted`() {
        val result = terminals.getValue(CANCELLED).then(reported(ProviderStatus.PAID))
        assertEquals(CANCELLED, result.state)
        assertEquals(
            listOf<Effect>(Effect.RaiseAlert(AlertReason.CONTRADICTING_EVENT_AFTER_TERMINAL)),
            result.steps.single().effects,
        )
    }

    @Test
    fun `provider event that contradicts the current state moves the payment to MISMATCH`() {
        // A late reject after acceptance is a return flow, not a transition.
        val result = accepted.then(reported(ProviderStatus.REJECTED))
        assertEquals(MISMATCH, result.state)
        assertEquals(listOf(Outcome.NO_PATH), result.outcomes())
        // MISMATCH is internal: partners are not told, a person is.
        assertEquals(listOf<Effect>(Effect.RaiseAlert(AlertReason.MISMATCH)), result.steps.single().effects)
    }

    @Test
    fun `provider event for a payment that was never sent is a mismatch, never inferred`() {
        for (notSent in unsent) {
            val result = notSent.then(reported(ProviderStatus.PAID))
            assertEquals(MISMATCH, result.state, "from ${notSent.state}")
            assertEquals(listOf(Outcome.NO_PATH), result.outcomes())
        }
    }

    @Test
    fun `payment in MISMATCH ignores further provider events`() {
        val result = mismatch.then(reported(ProviderStatus.PAID))
        assertEquals(MISMATCH, result.state)
        assertEquals(listOf(Outcome.STALE), result.outcomes())
    }

    // --- Timeout and UNKNOWN ------------------------------------------------------------------

    @Test
    fun `timeout while waiting on the downstream leads to UNKNOWN and queues reconciliation`() {
        val inCancellation = run(CancelRequested, from = sending)
        for (waiting in listOf(sending, presented, accepted, inCancellation)) {
            val result = waiting.then(ResponseTimeout)
            assertEquals(UNKNOWN, result.state, "from ${waiting.state}")
            assertEquals(listOf<Effect>(Effect.EnqueueReconciliation), result.steps.single().effects)
        }
    }

    @Test
    fun `timeout keeps the last known position`() {
        assertEquals(ACCEPTED, accepted.then(ResponseTimeout).context.position)
        assertEquals(PRESENTED, presented.then(ResponseTimeout).context.position)
    }

    @Test
    fun `timeout never produces a failed state`() {
        val failed = setOf(REJECTED, REFUSED, EXPIRED, CANCELLED, MISMATCH)
        for (ctx in unsent + sending + presented + accepted + terminals.values + mismatch) {
            assertFalse(ctx.then(ResponseTimeout).state in failed - ctx.state, "from ${ctx.state}")
        }
    }

    @Test
    fun `timeout that fires after the payment moved on is stale`() {
        for (ctx in listOf(terminals.getValue(PAID), run(ResponseTimeout, from = sending), mismatch, unsent[0])) {
            val result = ctx.then(ResponseTimeout)
            assertEquals(ctx, result.context)
            assertEquals(listOf(Outcome.STALE), result.outcomes())
        }
    }

    @Test
    fun `a late event after a timeout resolves UNKNOWN from the last known position`() {
        val unknownAfterSend = run(ResponseTimeout, from = sending)
        assertEquals(PRESENTED, unknownAfterSend.then(reported(ProviderStatus.PRESENTED)).state)
        assertEquals(PAID, unknownAfterSend.then(reported(ProviderStatus.PAID)).state)
        assertEquals(REJECTED, unknownAfterSend.then(reported(ProviderStatus.REJECTED)).state)
    }

    @Test
    fun `a payment that timed out after acceptance resolves without repeating partner statuses`() {
        val statuses = partnerStatuses(
            reported(ProviderStatus.PRESENTED), reported(ProviderAccepted), ResponseTimeout,
            reported(ProviderStatus.PAID),
            from = sending,
        )
        assertEquals(listOf(PRESENTED, ACCEPTED, PAID), statuses)
    }

    @Test
    fun `after a timeout an event behind the known position is stale, never moving backwards`() {
        val unknown = accepted.then(ResponseTimeout).context
        val result = unknown.then(reported(ProviderStatus.PRESENTED))
        assertEquals(unknown, result.context)
        assertEquals(listOf(Outcome.STALE), result.outcomes())
    }

    @Test
    fun `after a timeout a contradicting event is still a mismatch`() {
        // Same answer with or without the timeout in between.
        assertEquals(MISMATCH, accepted.then(ResponseTimeout).context.then(reported(ProviderStatus.REJECTED)).state)
        assertEquals(MISMATCH, presented.then(ResponseTimeout).context.then(reported(ProviderStatus.REJECTED)).state)
        assertEquals(MISMATCH, presented.then(reported(ProviderStatus.REJECTED)).state)
    }

    // --- Cancellation -------------------------------------------------------------------------

    @Test
    fun `cancelling before the send cancels immediately`() {
        for (notSent in unsent) {
            assertEquals(CANCELLED, notSent.then(CancelRequested).state, "from ${notSent.state}")
        }
    }

    @Test
    fun `cancelling in flight is forwarded downstream`() {
        for (inFlight in listOf(sending, presented)) {
            val result = inFlight.then(CancelRequested)
            assertEquals(CANCELLATION_REQUESTED, result.state)
            assertTrue(result.context.cancelRequested)
            assertEquals(
                listOf(Effect.PartnerStatus(CANCELLATION_REQUESTED), Effect.ForwardCancellation),
                result.steps.single().effects,
            )
        }
    }

    @Test
    fun `cancelling an UNKNOWN payment is forwarded because it may still be in flight`() {
        val result = presented.then(ResponseTimeout).context.then(CancelRequested)
        assertEquals(CANCELLATION_REQUESTED, result.state)
        assertEquals(listOf<Effect>(Effect.PartnerStatus(CANCELLATION_REQUESTED), Effect.ForwardCancellation), result.steps.single().effects)
    }

    @Test
    fun `cancelling after acceptance is too late and recorded as stale`() {
        val contexts = listOf(accepted, accepted.then(ResponseTimeout).context, mismatch) + terminals.values
        for (late in contexts) {
            val result = late.then(CancelRequested)
            assertEquals(late, result.context, "from ${late.state}")
            assertEquals(listOf(Outcome.STALE), result.outcomes())
        }
    }

    @Test
    fun `a second cancellation request is stale`() {
        val requested = presented.then(CancelRequested).context
        assertEquals(listOf(Outcome.STALE), requested.then(CancelRequested).outcomes())
        val unknown = requested.then(ResponseTimeout).context
        assertEquals(listOf(Outcome.STALE), unknown.then(CancelRequested).outcomes())
    }

    @Test
    fun `downstream confirms a requested cancellation`() {
        val requested = presented.then(CancelRequested).context
        assertEquals(CANCELLED, requested.then(reported(ProviderStatus.CANCELLED)).state)
    }

    @Test
    fun `a late PRESENTED acknowledgement does not undo a requested cancellation`() {
        // Common race: the cancel was forwarded before the presentment acknowledgement arrived.
        val requested = sending.then(CancelRequested).context
        val late = requested.then(reported(ProviderStatus.PRESENTED))
        assertEquals(CANCELLATION_REQUESTED, late.state)
        assertEquals(PRESENTED, late.context.position)
        assertTrue(late.steps.flatMap { it.effects }.none { it is Effect.PartnerStatus })
        assertEquals(CANCELLED, late.context.then(reported(ProviderStatus.CANCELLED)).state)
    }

    @Test
    fun `a cancellation survives a timeout and a late acknowledgement`() {
        val ctx = run(CancelRequested, ResponseTimeout, reported(ProviderStatus.PRESENTED), from = sending)
        assertEquals(CANCELLATION_REQUESTED, ctx.state)
        assertEquals(CANCELLED, ctx.then(reported(ProviderStatus.CANCELLED)).state)
    }

    @Test
    fun `a confirmed cancellation is accepted after a timeout`() {
        val unknown = run(CancelRequested, ResponseTimeout, from = presented)
        assertEquals(UNKNOWN, unknown.state)
        assertEquals(CANCELLED, unknown.then(reported(ProviderStatus.CANCELLED)).state)
    }

    @Test
    fun `a cancellation confirmation after the debtor accepted is a mismatch, with or without a timeout`() {
        val accepted = run(CancelRequested, reported(ProviderAccepted), from = presented)
        assertEquals(ACCEPTED, accepted.state)
        val unknown = accepted.then(ResponseTimeout).context
        assertEquals(UNKNOWN, unknown.state)
        assertEquals(MISMATCH, unknown.then(reported(ProviderStatus.CANCELLED)).state)
        assertEquals(MISMATCH, accepted.then(reported(ProviderStatus.CANCELLED)).state)
    }

    @Test
    fun `a cancellation nobody requested cannot be confirmed`() {
        assertEquals(MISMATCH, presented.then(reported(ProviderStatus.CANCELLED)).state)
        assertEquals(MISMATCH, sending.then(ResponseTimeout).context.then(reported(ProviderStatus.CANCELLED)).state)
    }

    @Test
    fun `debtor acceptance wins the race against a requested cancellation without repeating PRESENTED`() {
        val statuses = partnerStatuses(CancelRequested, reported(ProviderAccepted), from = presented)
        assertEquals(listOf(CANCELLATION_REQUESTED, ACCEPTED), statuses)
        assertEquals(ACCEPTED, presented.then(CancelRequested).context.then(reported(ProviderAccepted)).state)
    }

    @Test
    fun `a reject after a requested cancellation from PRESENTED is a mismatch like without it`() {
        assertEquals(MISMATCH, presented.then(CancelRequested).context.then(reported(ProviderStatus.REJECTED)).state)
    }

    // --- Properties over every reachable sequence ---------------------------------------------

    /**
     * Explores every sequence of inputs from RECEIVED up to [depth] inputs that changes the context
     * each time (stale and invalid inputs are checked but not extended), and calls [visit] with the
     * partner statuses seen on the way.
     */
    private fun explore(depth: Int, visit: (Context, Transition, List<State>, List<Input>) -> Unit) {
        fun walk(ctx: Context, remaining: Int, statuses: List<State>, history: List<Input>) {
            for (input in allInputs) {
                val result = transition(ctx, input)
                val path = history + input
                val next = (result as? Transition.Valid)?.context
                val seen = statuses + (result as? Transition.Valid)?.steps.orEmpty()
                    .flatMap { it.effects }.filterIsInstance<Effect.PartnerStatus>().map { it.state }
                visit(ctx, result, seen, path)
                if (next != null && next != ctx && remaining > 1) walk(next, remaining - 1, seen, path)
            }
        }
        walk(Context.initial, depth, emptyList(), emptyList())
    }

    @Test
    fun `every reachable transition is well-formed`() {
        var valid = 0
        var invalid = 0
        explore(9) { ctx, result, _, path ->
            if (result is Transition.Valid) {
                valid++
                assertWellFormed(ctx, path, result)
            } else {
                invalid++
                assertIs<Advance>(path.last(), "Invalid only for worker steps: $path")
            }
        }
        // Non-trivial: both kinds of result occurred, so the checks above ran on real data.
        assertTrue(valid > 500 && invalid > 500, "valid=$valid invalid=$invalid")
    }

    @Test
    fun `partners never see a repeated status, an internal state or a status after a final one`() {
        explore(9) { _, _, statuses, path ->
            assertTrue(statuses.all { it.partnerVisible }, "internal state leaked on $path")
            assertTrue(statuses.zipWithNext().none { (a, b) -> a == b }, "repeated status $statuses on $path")
            val firstFinal = statuses.indexOfFirst { it.terminal }
            if (firstFinal >= 0) assertEquals(firstFinal, statuses.lastIndex, "status after final: $statuses on $path")
        }
    }

    @Test
    fun `a payment only becomes CANCELLED by a cancellation that was requested`() {
        explore(9) { ctx, result, _, path ->
            val cancelled = (result as? Transition.Valid)?.state == CANCELLED && ctx.state != CANCELLED
            if (cancelled) {
                assertTrue(
                    path.any { it == CancelRequested },
                    "CANCELLED without a CancelRequested: $path",
                )
            }
        }
    }

    @Test
    fun `a payment never moves backwards on the main path`() {
        val rank = listOf(SENDING, PRESENTED, ACCEPTED, PAID)
        explore(9) { ctx, result, _, path ->
            val position = (result as? Transition.Valid)?.context?.position ?: return@explore
            val before = ctx.position ?: return@explore
            assertTrue(rank.indexOf(position) >= rank.indexOf(before), "position moved back on $path")
        }
    }

    @Test
    fun `an effect is never emitted twice for one step and outbox kinds never collide`() {
        explore(9) { _, result, _, path ->
            for (step in (result as? Transition.Valid)?.steps.orEmpty()) {
                val kinds = step.effects.map { it::class }
                assertEquals(kinds.size, kinds.toSet().size, "duplicate kind in one step on $path")
            }
        }
    }

    @Test
    fun `terminal and MISMATCH payments never change`() {
        for (ctx in terminals.values + mismatch) {
            for (input in allInputs) {
                val result = transition(ctx, input)
                if (result is Transition.Valid) assertEquals(ctx.state, result.state, "$input in ${ctx.state}")
            }
        }
    }

    @Test
    fun `only non-terminal non-waiting states have automatic work`() {
        val withWork = State.entries.filter { it.hasAutomaticWork }.toSet()
        assertEquals(
            setOf(
                RECEIVED, VALIDATED, PENDING_SCREENING, READY_TO_SEND, SENDING, PRESENTED, ACCEPTED,
                CANCELLATION_REQUESTED,
            ),
            withWork,
        )
    }

    @Test
    fun `partners only see downstream-facing states`() {
        assertEquals(
            setOf(PRESENTED, ACCEPTED, CANCELLATION_REQUESTED, PAID, REJECTED, REFUSED, EXPIRED, CANCELLED),
            State.entries.filter { it.partnerVisible }.toSet(),
        )
    }

    private fun assertWellFormed(ctx: Context, path: List<Input>, result: Transition.Valid) {
        val label = "${path.last()} after ${path.dropLast(1)}"
        assertTrue(result.steps.isNotEmpty(), label)
        assertEquals(ctx.state, result.steps.first().from, label)
        result.steps.zipWithNext().forEach { (a, b) -> assertEquals(a.to, b.from, label) }
        // Only the last step is APPLIED; everything before it was inferred.
        assertTrue(result.steps.dropLast(1).all { it.outcome == Outcome.INFERRED }, label)
        assertEquals(result.steps.last().to, result.state, label)

        when (result.steps.last().outcome) {
            Outcome.STALE -> {
                assertEquals(1, result.steps.size, label)
                assertEquals(ctx, result.context, label)
                // A stale step changes nothing, so the only thing it may carry is an alert.
                assertTrue(result.steps.single().effects.all { it is Effect.RaiseAlert }, label)
            }
            Outcome.NO_PATH -> {
                assertEquals(1, result.steps.size, label)
                assertEquals(MISMATCH, result.state, label)
            }
            Outcome.APPLIED -> Unit
            Outcome.INFERRED -> error("The last step cannot be inferred: $label")
        }
        // Every partner-visible state entered gets a status, inferred ones included (so a skipped
        // state's side effects are not lost); internal states get none. The one exception: a PRESENTED
        // acknowledgement during a cancellation only moves the known position, the partner already
        // saw CANCELLATION_REQUESTED.
        val positionOnly = path.last() == ProviderReported(ProviderStatus.PRESENTED) && result.state == CANCELLATION_REQUESTED
        for (step in result.steps.filter { it.outcome == Outcome.APPLIED || it.outcome == Outcome.INFERRED }) {
            assertEquals(
                step.to.partnerVisible && !positionOnly,
                Effect.PartnerStatus(step.to) in step.effects,
                label,
            )
        }
        // The known position only ever points at an in-flight state, and is set once the payment was sent.
        result.context.position?.let { assertTrue(it in setOf(SENDING, PRESENTED, ACCEPTED), label) }
        if (result.state in setOf(SENDING, PRESENTED, ACCEPTED, UNKNOWN, CANCELLATION_REQUESTED)) {
            assertTrue(result.context.position != null, label)
        }
    }
}
