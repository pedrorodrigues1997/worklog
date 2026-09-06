-- The organization's subscription. Columns follow the agreed product DBML; the two
-- additions are noted below.
--
-- **This table is a cache of Stripe's state, not the source of truth.** Nothing here is
-- decided locally: every column is written from a signature-verified webhook describing what
-- Stripe already believes. The application reads it to answer "what plan is this
-- organization on, is it active, how many seats, when does the period end" without a network
-- call on every request.
--
-- One row per organization, and it is the *current* subscription. Superseding one (cancel,
-- then subscribe again) overwrites the row rather than appending - billing history lives in
-- Stripe, which is the system that will still have it in five years.

CREATE TABLE subscriptions (
    id                       UUID         NOT NULL,
    organization_id          UUID         NOT NULL,
    -- SubscriptionPlan. Resolved from the Stripe price id through configuration, never
    -- stored as a price id: a price can be replaced in Stripe without the plan changing.
    plan                     VARCHAR(32)  NOT NULL,
    -- SubscriptionStatus, mapped one-to-one from Stripe's own subscription status. See
    -- SubscriptionStatus.fromStripe for the mapping and why it refuses to guess.
    status                   VARCHAR(32)  NOT NULL,
    seat_quantity            INTEGER      NOT NULL,

    provider                 VARCHAR(32)  NOT NULL,
    provider_subscription_id VARCHAR(255),

    current_period_start     TIMESTAMPTZ,
    current_period_end       TIMESTAMPTZ,

    created_at               TIMESTAMPTZ  NOT NULL,
    updated_at               TIMESTAMPTZ  NOT NULL,
    -- When cancellation was *requested*, mirroring Stripe's canceled_at. Not when access
    -- ends: a subscription cancelled at period end keeps status ACTIVE and a future
    -- current_period_end, and only becomes CANCELED when Stripe says so.
    cancelled_at             TIMESTAMPTZ,
    -- Added beyond the DBML, and necessary. Stripe represents "cancel at period end" as a
    -- flag on a subscription whose status is still `active`; without this column the two
    -- cancellation semantics are indistinguishable locally, and a client cannot tell "you
    -- are paid up until the 30th" from "you renewed".
    cancel_at_period_end     BOOLEAN      NOT NULL DEFAULT FALSE,

    CONSTRAINT pk_subscriptions PRIMARY KEY (id),
    -- Restricts, like billing_customers: real money is attached to this row.
    CONSTRAINT fk_subscriptions_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (organization_id),

    CONSTRAINT ck_subscriptions_seat_quantity_positive CHECK (seat_quantity >= 0),
    CONSTRAINT ck_subscriptions_period_ordered
        CHECK (current_period_end IS NULL OR current_period_start IS NULL
               OR current_period_end > current_period_start)
);

-- One subscription per organization, from the DBML and load-bearing: it is what makes a
-- duplicate webhook delivery unable to produce a second subscription even if every other
-- guard failed.
CREATE UNIQUE INDEX ux_subscriptions_organization ON subscriptions (organization_id);

-- One local row per provider subscription. Nullable because a row can exist before Stripe
-- has confirmed one; partial so those nulls do not collide with each other.
CREATE UNIQUE INDEX ux_subscriptions_provider_subscription
    ON subscriptions (provider, provider_subscription_id)
    WHERE provider_subscription_id IS NOT NULL;
