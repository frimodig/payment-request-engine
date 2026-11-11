-- Initial schema. See docs/database.md for the rules each table enforces.

CREATE TABLE partner (
    id              uuid        PRIMARY KEY,
    external_ref    text        NOT NULL UNIQUE,
    name            text        NOT NULL,
    acceptance_mode text        NOT NULL DEFAULT 'PARTIAL'
                                CHECK (acceptance_mode IN ('PARTIAL', 'ALL_OR_NOTHING')),
    max_in_flight   integer     NOT NULL DEFAULT 100 CHECK (max_in_flight > 0),
    paused          boolean     NOT NULL DEFAULT false,
    created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE submission (
    id              uuid        PRIMARY KEY,
    partner_id      uuid        NOT NULL REFERENCES partner (id),
    channel         text        NOT NULL CHECK (channel IN ('API', 'FILE')),
    message_id      text        NOT NULL,
    received_at     timestamptz NOT NULL DEFAULT now(),
    file_object_key text,
    declared_count  integer,
    declared_sum_minor bigint,
    status          text        NOT NULL DEFAULT 'RECEIVED'
                                CHECK (status IN ('RECEIVED', 'SPLIT', 'REJECTED')),
    UNIQUE (partner_id, message_id)
);

CREATE TABLE payment_request (
    id                          uuid        PRIMARY KEY,
    partner_id                  uuid        NOT NULL REFERENCES partner (id),
    submission_id               uuid        NOT NULL REFERENCES submission (id),
    end_to_end_id               text        NOT NULL,
    content_hash                text        NOT NULL,
    parent_ref                  text,
    -- State values left unconstrained while the state list is a draft (docs/architecture.md).
    state                       text        NOT NULL,
    amount_minor                bigint      NOT NULL CHECK (amount_minor > 0),
    currency                    char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    amount_modification_allowed boolean     NOT NULL DEFAULT false,
    expires_at                  timestamptz,
    debtor_identification       text,
    creditor_identification     text,
    remittance_reference        text,
    downstream                  text,
    downstream_ref              text,
    send_attempt                integer     NOT NULL DEFAULT 0,
    -- Null: no automatic work due (terminal, or waiting on a person, e.g. UNKNOWN, MISMATCH).
    next_action_at              timestamptz,
    lease_owner                 text,
    lease_until                 timestamptz,
    lease_token                 bigint      NOT NULL DEFAULT 0,
    event_seq                   integer     NOT NULL DEFAULT 0,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (partner_id, end_to_end_id)
);

-- Worker claim query, timer path: rows with automatic work due. (Rows with unapplied
-- inputs are found through the inbox, manual_action and cancellation_request indexes.)
CREATE INDEX payment_request_due_idx
    ON payment_request (next_action_at)
    WHERE next_action_at IS NOT NULL;

-- One payment per downstream reference, so a provider event can never match two payments.
CREATE UNIQUE INDEX payment_request_downstream_ref_idx
    ON payment_request (downstream, downstream_ref)
    WHERE downstream_ref IS NOT NULL;

CREATE INDEX payment_request_submission_idx ON payment_request (submission_id);

CREATE TABLE manual_action (
    id            uuid        PRIMARY KEY,
    payment_id    uuid        NOT NULL REFERENCES payment_request (id),
    action        text        NOT NULL,
    reason        text        NOT NULL,
    requested_by  text        NOT NULL,
    requested_at  timestamptz NOT NULL DEFAULT now(),
    approved_by   text,
    approved_at   timestamptz,
    status        text        NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    applied_at    timestamptz,
    CHECK (approved_by IS NULL OR approved_by <> requested_by),
    -- A decision always has a decider and a time; a pending action has neither.
    CHECK ((status = 'PENDING') = (approved_by IS NULL)),
    CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CHECK (applied_at IS NULL OR status = 'APPROVED')
);

CREATE INDEX manual_action_payment_idx ON manual_action (payment_id);
CREATE INDEX manual_action_unapplied_idx ON manual_action (payment_id)
    WHERE status = 'APPROVED' AND applied_at IS NULL;

-- Partner cancellation requests: an input the worker applies under a lease.
CREATE TABLE cancellation_request (
    id           uuid        PRIMARY KEY,
    payment_id   uuid        NOT NULL REFERENCES payment_request (id),
    requested_by text        NOT NULL,
    requested_at timestamptz NOT NULL DEFAULT now(),
    applied_at   timestamptz,
    UNIQUE (payment_id)
);

CREATE INDEX cancellation_request_unapplied_idx ON cancellation_request (payment_id)
    WHERE applied_at IS NULL;

CREATE TABLE provider_inbox (
    id                 bigserial   PRIMARY KEY,
    provider           text        NOT NULL,
    provider_event_id  text        NOT NULL,
    provider_timestamp timestamptz,
    received_at        timestamptz NOT NULL DEFAULT now(),
    downstream_ref     text,
    payment_id         uuid        REFERENCES payment_request (id),
    status             text        NOT NULL DEFAULT 'NEW'
                                   CHECK (status IN ('NEW', 'APPLIED', 'PARKED')),
    payload            jsonb       NOT NULL,
    UNIQUE (provider, provider_event_id),
    -- Parked if and only if not yet linked to a payment.
    CHECK ((status = 'PARKED') = (payment_id IS NULL))
);

CREATE INDEX provider_inbox_payment_idx ON provider_inbox (payment_id) WHERE status = 'NEW';
CREATE INDEX provider_inbox_parked_idx ON provider_inbox (provider, downstream_ref) WHERE status = 'PARKED';

-- Append-only. Grant the application role INSERT and SELECT only (per environment; not in this migration).
-- Sequence uniqueness is enforced through payment_request.event_seq in the fenced update;
-- a partitioned table cannot enforce UNIQUE (payment_id, seq) without the partition key.
CREATE TABLE payment_event (
    payment_id       uuid        NOT NULL REFERENCES payment_request (id),
    seq              integer     NOT NULL,
    type             text        NOT NULL,
    from_state       text,
    to_state         text,
    outcome          text        NOT NULL
                                 CHECK (outcome IN ('APPLIED', 'INFERRED', 'STALE', 'NO_PATH')),
    category         text        NOT NULL CHECK (category IN ('BUSINESS', 'TECHNICAL')),
    actor_type       text        NOT NULL
                                 CHECK (actor_type IN ('PARTNER', 'SERVICE', 'DOWNSTREAM', 'ADMIN')),
    actor_id         text        NOT NULL,
    reason           text,
    manual_action_id uuid        REFERENCES manual_action (id),
    correlation_id   text,
    inbox_event_id   bigint      REFERENCES provider_inbox (id),
    payload          jsonb,
    occurred_at      timestamptz NOT NULL,
    recorded_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (payment_id, seq, recorded_at),
    CHECK (actor_type <> 'ADMIN' OR (reason IS NOT NULL AND manual_action_id IS NOT NULL))
) PARTITION BY RANGE (recorded_at);

-- Monthly partitions are created ahead by a scheduled job. The default partition is a
-- safety net only: it should stay empty, and a row in it is an alert (it blocks creating
-- a partition for that range).
CREATE TABLE payment_event_2026_11 PARTITION OF payment_event
    FOR VALUES FROM ('2026-11-01 00:00:00+00') TO ('2026-12-01 00:00:00+00');
CREATE TABLE payment_event_2026_12 PARTITION OF payment_event
    FOR VALUES FROM ('2026-12-01 00:00:00+00') TO ('2027-01-01 00:00:00+00');
CREATE TABLE payment_event_2027_01 PARTITION OF payment_event
    FOR VALUES FROM ('2027-01-01 00:00:00+00') TO ('2027-02-01 00:00:00+00');
CREATE TABLE payment_event_default PARTITION OF payment_event DEFAULT;

-- One row per side effect: either for one payment (keyed on its event sequence) or for a
-- whole submission (e.g. a file-level status report or an all-or-nothing rejection).
CREATE TABLE outbox (
    id              bigserial   PRIMARY KEY,
    payment_id      uuid        REFERENCES payment_request (id),
    event_seq       integer,
    submission_id   uuid        REFERENCES submission (id),
    kind            text        NOT NULL,
    payload         jsonb       NOT NULL,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    attempts        integer     NOT NULL DEFAULT 0,
    delivered_at    timestamptz,
    CHECK (num_nonnulls(payment_id, submission_id) = 1),
    CHECK ((payment_id IS NULL) = (event_seq IS NULL)),
    UNIQUE (payment_id, event_seq, kind),
    UNIQUE (submission_id, kind)
);

CREATE INDEX outbox_due_idx
    ON outbox (next_attempt_at)
    WHERE delivered_at IS NULL;
