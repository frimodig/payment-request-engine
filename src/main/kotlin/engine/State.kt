package engine

// Draft state list from docs/architecture.md; the schema leaves state values unconstrained until it is frozen.
enum class State(val terminal: Boolean = false, val waitsForPerson: Boolean = false) {
    RECEIVED,
    VALIDATED,
    PENDING_SCREENING,
    READY_TO_SEND,
    SENDING,
    PRESENTED,
    ACCEPTED,
    CANCELLATION_REQUESTED,

    UNKNOWN(waitsForPerson = true),
    MISMATCH(waitsForPerson = true),

    PAID(terminal = true),
    REJECTED(terminal = true),
    REFUSED(terminal = true),
    EXPIRED(terminal = true),
    CANCELLED(terminal = true),
    ;

    // Null next_action_at in the database means exactly this: no automatic work is due.
    val hasAutomaticWork: Boolean get() = !terminal && !waitsForPerson

    // Sent, and only the downstream can move it on; the worker acts on silence after a response window.
    val waitsForDownstream: Boolean
        get() = when (this) {
            SENDING, PRESENTED, ACCEPTED, CANCELLATION_REQUESTED -> true
            else -> false
        }

    // Status values are a partner contract and published ones are forever (docs/architecture.md), so
    // internal states stay out: validation, screening, sending, UNKNOWN and MISMATCH.
    // RECEIVED is acknowledged at ingestion, not by a transition.
    val partnerVisible: Boolean
        get() = when (this) {
            PRESENTED, ACCEPTED, CANCELLATION_REQUESTED, PAID, REJECTED, REFUSED, EXPIRED, CANCELLED -> true
            else -> false
        }
}

/** What a downstream can tell us about a payment. Each value names the state it reports. */
enum class ProviderStatus(val reports: State) {
    PRESENTED(State.PRESENTED),
    ACCEPTED(State.ACCEPTED),
    PAID(State.PAID),
    REJECTED(State.REJECTED),
    REFUSED(State.REFUSED),
    EXPIRED(State.EXPIRED),
    CANCELLED(State.CANCELLED),
}
