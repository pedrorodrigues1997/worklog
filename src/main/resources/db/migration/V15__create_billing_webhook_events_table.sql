-- Every webhook event this backend has already dealt with.
--
-- Stripe delivers at least once, not exactly once: a delivery that times out, or whose
-- response is lost, is retried with the same event id. Without this table a retried
-- `customer.subscription.created` would be processed twice.
--
-- The mechanism is deliberately the simplest one that cannot be got wrong: the event id is
-- inserted here in the *same transaction* as the state change it authorises. A duplicate
-- delivery violates the unique index and the whole transaction rolls back, having changed
-- nothing. Two simultaneous deliveries serialise on the index rather than racing.
--
-- Note what this is not: it is not an audit log, and it is not a queue. It records that an
-- id was handled, and nothing that would grow without bound in width.

CREATE TABLE billing_webhook_events (
    id          UUID         NOT NULL,
    provider    VARCHAR(32)  NOT NULL,
    -- The provider's own event id ("evt_..."), which is stable across retries of the same
    -- event and is what makes this work.
    event_id    VARCHAR(255) NOT NULL,
    event_type  VARCHAR(255) NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_billing_webhook_events PRIMARY KEY (id)
);

-- The idempotency guarantee itself. Scoped by provider so two providers cannot collide on
-- an id format neither of them controls.
CREATE UNIQUE INDEX ux_billing_webhook_events_provider_event
    ON billing_webhook_events (provider, event_id);

-- Housekeeping: these rows only need to outlive Stripe's retry window (days), so a future
-- cleanup job will delete by age the way RefreshTokenCleanupJob does.
CREATE INDEX ix_billing_webhook_events_received_at ON billing_webhook_events (received_at);
