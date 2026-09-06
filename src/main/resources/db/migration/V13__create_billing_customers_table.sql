-- The organization's identity with a payment provider.
--
-- Shaped deliberately like `user_identities`, which solves the same problem one level down:
-- an internal entity linked to an external provider's identifier, keyed by provider. The
-- precedent decides the shape here.
--
-- A separate table rather than a `stripe_customer_id` column on `organizations`, for the
-- same reasons user_identities is not columns on `users`: a provider-specific column on the
-- core tenant table means a schema change per provider and a wide row of nulls, and
-- `organizations` has no business knowing that Stripe exists.
--
-- Separate from `subscriptions` because the customer outlives any individual subscription.
-- It is created before the first checkout - so a customer is not minted on every attempt -
-- and it survives cancellation, which is what makes the billing portal, invoices and
-- re-subscribing work later.

CREATE TABLE billing_customers (
    id                   UUID         NOT NULL,
    organization_id      UUID         NOT NULL,
    -- Currently only STRIPE. Kept as text, like user_identities.provider, so a second
    -- provider needs no schema change.
    provider             VARCHAR(32)  NOT NULL,
    -- The provider's own customer identifier ("cus_..."). This is the *only* mapping from a
    -- provider event back to a TicTac organization - webhook payload metadata is never
    -- trusted for that. See StripeWebhookService.
    provider_customer_id VARCHAR(255) NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_billing_customers PRIMARY KEY (id),
    -- Restricts rather than cascades: this is the link to a real customer record at a
    -- payment provider, with real invoices behind it. A hard delete of an organization
    -- should fail loudly rather than quietly orphan a Stripe customer that is still being
    -- billed. Nothing in the application hard-deletes an organization anyway.
    CONSTRAINT fk_billing_customers_organization
        FOREIGN KEY (organization_id) REFERENCES organizations (organization_id)
);

-- One customer per organization per provider. This is what makes "find or create" safe:
-- two concurrent checkouts both try to insert, and only one can.
CREATE UNIQUE INDEX ux_billing_customers_organization_provider
    ON billing_customers (organization_id, provider);

-- ...and one organization per provider customer, which is the direction webhooks read. Two
-- organizations sharing a Stripe customer would make an incoming subscription ambiguous.
CREATE UNIQUE INDEX ux_billing_customers_provider_customer
    ON billing_customers (provider, provider_customer_id);
