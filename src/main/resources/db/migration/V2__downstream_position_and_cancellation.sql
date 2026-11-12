-- The state machine needs two facts the state alone cannot give once a payment is UNKNOWN or
-- CANCELLATION_REQUESTED: how far the downstream got, and whether a cancellation was requested.
-- See docs/database.md (payment_request).

ALTER TABLE payment_request
    -- Last known downstream state. Null until the payment was sent; kept across UNKNOWN and CANCELLATION_REQUESTED.
    ADD COLUMN downstream_position text CHECK (downstream_position IN ('SENDING', 'PRESENTED', 'ACCEPTED')),
    -- A partner cancellation was applied. Only then can a downstream cancellation confirmation be accepted.
    ADD COLUMN cancel_requested boolean NOT NULL DEFAULT false;
