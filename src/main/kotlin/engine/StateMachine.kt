package engine

/**
 * What the machine needs to know about a payment. [position] is the last known downstream state
 * (SENDING, PRESENTED or ACCEPTED); it survives UNKNOWN and CANCELLATION_REQUESTED, which would
 * otherwise forget how far the payment got. Null until the payment has been sent.
 */
data class Context(val state: State, val position: State? = null, val cancelRequested: Boolean = false) {
    companion object {
        val initial = Context(State.RECEIVED)
    }
}

/** Something the lease holder applies to a payment. */
sealed interface Input {
    /** The worker's own next step. Exactly one edge, never skips a state. */
    data class Advance(val to: State) : Input

    /** A downstream event. The state graph decides what it means; its timestamp never does. */
    data class ProviderReported(val status: ProviderStatus) : Input

    /** A partner cancellation request. */
    data object CancelRequested : Input

    /** The expected response window passed without an answer. */
    data object ResponseTimeout : Input
}

/** Side effect, returned as data. The worker persists each one as an outbox row in the same transaction. */
sealed interface Effect {
    /** The only effect that is a delivery to a partner. */
    data class PartnerStatus(val state: State) : Effect

    data object ForwardCancellation : Effect
    data object EnqueueReconciliation : Effect
    data class RaiseAlert(val reason: AlertReason) : Effect
}

enum class AlertReason {
    /** The payment contradicts what the downstream reports and needs a person. */
    MISMATCH,

    /** A downstream reported a different final outcome for a payment that was already final. */
    CONTRADICTING_EVENT_AFTER_TERMINAL,
}

/** Same values as payment_event.outcome. */
enum class Outcome { APPLIED, INFERRED, STALE, NO_PATH }

/**
 * One payment_event row. Effects belong to the step, so each outbox row is keyed on that step's
 * event sequence number (payment_event.seq).
 */
data class Step(val from: State, val to: State, val outcome: Outcome, val effects: List<Effect> = emptyList())

sealed interface Transition {
    /** [context] is the payment after the steps. STALE steps leave it unchanged. */
    data class Valid(val context: Context, val steps: List<Step>) : Transition {
        val state: State get() = context.state
    }

    /** The input cannot be applied in this state, and nothing should be recorded as if it had been. */
    data class Invalid(val reason: String) : Transition
}

object PaymentStateMachine {
    private val workerEdges: Map<State, Set<State>> = mapOf(
        State.RECEIVED to setOf(State.VALIDATED, State.REJECTED),
        State.VALIDATED to setOf(State.PENDING_SCREENING, State.REJECTED),
        State.PENDING_SCREENING to setOf(State.READY_TO_SEND, State.REJECTED),
        State.READY_TO_SEND to setOf(State.SENDING),
    )

    // The downstream part of the graph. A provider event can only be applied once the payment was
    // sent: it must never skip validation, screening or the send itself.
    private val providerEdges: Map<State, List<State>> = mapOf(
        State.SENDING to listOf(State.PRESENTED, State.REJECTED),
        State.PRESENTED to listOf(State.ACCEPTED, State.REFUSED, State.EXPIRED),
        State.ACCEPTED to listOf(State.PAID),
    )

    // Position on the main path, to tell "behind or equal" (stale) from "no path" (mismatch).
    private val mainPathRank = mapOf(State.SENDING to 0, State.PRESENTED to 1, State.ACCEPTED to 2, State.PAID to 3)

    private val inFlight = setOf(State.SENDING, State.PRESENTED, State.ACCEPTED)
    private val cancelPending = setOf(State.CANCELLATION_REQUESTED, State.UNKNOWN)
    private val cancellableBeforeSend = setOf(
        State.RECEIVED, State.VALIDATED, State.PENDING_SCREENING, State.READY_TO_SEND,
    )

    fun transition(ctx: Context, input: Input): Transition = when (input) {
        is Input.Advance -> advance(ctx, input.to)
        is Input.ProviderReported -> providerReported(ctx, input.status)
        Input.CancelRequested -> cancelRequested(ctx)
        Input.ResponseTimeout -> responseTimeout(ctx)
    }

    private fun advance(ctx: Context, to: State): Transition =
        if (to in workerEdges[ctx.state].orEmpty()) {
            applyPath(ctx, listOf(to))
        } else {
            Transition.Invalid("No worker transition from ${ctx.state} to $to")
        }

    private fun providerReported(ctx: Context, status: ProviderStatus): Transition {
        val current = ctx.state
        val target = status.reports
        if (current == State.MISMATCH) return stale(ctx)
        if (current.terminal) {
            // Recorded, never applied. A different final outcome (CANCELLED then PAID: money moved after
            // a cancellation) is parked for a person; an out-of-order or duplicate event is benign.
            val contradicts = target.terminal && target != current
            val alert = Effect.RaiseAlert(AlertReason.CONTRADICTING_EVENT_AFTER_TERMINAL)
            return stale(ctx, if (contradicts) listOf(alert) else emptyList())
        }
        val position = ctx.position ?: return noPath(ctx)

        if (target == State.CANCELLED) {
            // Only a cancellation we asked for can be confirmed.
            return if (ctx.cancelRequested && current in cancelPending) {
                applyPath(ctx, listOf(State.CANCELLED))
            } else {
                noPath(ctx)
            }
        }
        val behindOrEqual = mainPathRank[target]?.let { it <= mainPathRank.getValue(position) } ?: false
        if (behindOrEqual) return stale(ctx)

        val path = pathBetween(position, target) ?: return noPath(ctx)
        if (ctx.cancelRequested && current in cancelPending && path == listOf(State.PRESENTED)) {
            return presentedWhileCancelling(ctx)
        }
        return applyPath(ctx, path)
    }

    // The cancel was forwarded before presentment was acknowledged: the payment stays in its
    // cancellation, and only the known position moves on.
    private fun presentedWhileCancelling(ctx: Context): Transition.Valid {
        val next = ctx.copy(state = State.CANCELLATION_REQUESTED, position = State.PRESENTED)
        val effects = if (ctx.state == State.UNKNOWN) listOf<Effect>(Effect.ForwardCancellation) else emptyList()
        return Transition.Valid(next, listOf(Step(ctx.state, next.state, Outcome.APPLIED, effects)))
    }

    private fun cancelRequested(ctx: Context): Transition {
        val withRequest = ctx.copy(cancelRequested = true)
        return when {
            ctx.state in cancellableBeforeSend -> applyPath(withRequest, listOf(State.CANCELLED))
            ctx.state == State.SENDING || ctx.state == State.PRESENTED ->
                applyPath(withRequest, listOf(State.CANCELLATION_REQUESTED))
            // UNKNOWN may still be in flight, unless the debtor already accepted.
            ctx.state == State.UNKNOWN && !ctx.cancelRequested && ctx.position != State.ACCEPTED ->
                applyPath(withRequest, listOf(State.CANCELLATION_REQUESTED))
            // Too late (accepted, paid, terminal) or not meaningful (mismatch, already requested).
            else -> stale(ctx)
        }
    }

    // Unknown never expires into failed: a timeout can only lead to UNKNOWN.
    private fun responseTimeout(ctx: Context): Transition = when (ctx.state) {
        // ACCEPTED has a due time too (waiting for the credit transfer), so it times out the same way.
        State.SENDING, State.PRESENTED, State.ACCEPTED, State.CANCELLATION_REQUESTED ->
            applyPath(ctx, listOf(State.UNKNOWN))
        else -> stale(ctx)
    }

    /** States passed through after [from], ending at [to]; null if [to] cannot be reached. */
    private fun pathBetween(from: State, to: State): List<State>? {
        for (next in providerEdges[from].orEmpty()) {
            if (next == to) return listOf(to)
            pathBetween(next, to)?.let { return listOf(next) + it }
        }
        return null
    }

    private fun applyPath(ctx: Context, path: List<State>): Transition.Valid {
        val steps = path.mapIndexed { index, to ->
            val from = if (index == 0) ctx.state else path[index - 1]
            Step(
                from = from,
                to = to,
                outcome = if (index == path.lastIndex) Outcome.APPLIED else Outcome.INFERRED,
                effects = effectsOnEntering(to),
            )
        }
        val position = path.lastOrNull { it in inFlight } ?: ctx.position
        return Transition.Valid(ctx.copy(state = path.last(), position = position), steps)
    }

    private fun stale(ctx: Context, effects: List<Effect> = emptyList()) =
        Transition.Valid(ctx, listOf(Step(ctx.state, ctx.state, Outcome.STALE, effects)))

    // The payment contradicts what the downstream reports: park it for a person.
    private fun noPath(ctx: Context) =
        Transition.Valid(
            ctx.copy(state = State.MISMATCH),
            listOf(Step(ctx.state, State.MISMATCH, Outcome.NO_PATH, effectsOnEntering(State.MISMATCH))),
        )

    private fun effectsOnEntering(to: State): List<Effect> = buildList {
        if (to.partnerVisible) add(Effect.PartnerStatus(to))
        when (to) {
            State.CANCELLATION_REQUESTED -> add(Effect.ForwardCancellation)
            State.UNKNOWN -> add(Effect.EnqueueReconciliation)
            State.MISMATCH -> add(Effect.RaiseAlert(AlertReason.MISMATCH))
            else -> Unit
        }
    }
}
