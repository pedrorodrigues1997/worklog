-- The subscription stops describing a *plan* and starts describing a billing relationship.
--
-- TicTac sells no feature tiers: every organization has every feature, and the only thing
-- money buys is licenses. `plan` (FREE/PRO) was therefore describing something that does not
-- exist, and the one thing a customer genuinely chooses - how often they pay - had nowhere to
-- live. This migration swaps the former for the latter.
--
-- Nothing is deleted. Every existing row keeps its identity, its status, its quantity and its
-- dates; only the column describing "which tier" becomes "which interval".

-- 1. Seats become licenses.
--
-- A rename rather than a new column: it is the same number Stripe has always been sent, and
-- copying it into a second column would leave two places for it to drift. The value is
-- unchanged - paid licenses, one fewer than the organization holds.
ALTER TABLE subscriptions RENAME COLUMN seat_quantity TO paid_licenses;

-- The old constraint still guards the renamed column, but under a name that now describes
-- nothing. Dropped and re-added so the schema reads truthfully.
ALTER TABLE subscriptions DROP CONSTRAINT ck_subscriptions_seat_quantity_positive;
ALTER TABLE subscriptions
    ADD CONSTRAINT ck_subscriptions_paid_licenses_positive CHECK (paid_licenses >= 0);

-- 2. The billing interval.
--
-- Nullable for exactly one moment: the backfill below fills every existing row, and the NOT
-- NULL follows. Resolved from the Stripe price id through configuration, never stored as a
-- price id - a price can be replaced in Stripe without the interval changing.
ALTER TABLE subscriptions ADD COLUMN billing_interval VARCHAR(16);

-- Every subscription sold under the old model used the single configured price, which was
-- monthly. Recorded as such rather than guessed at read time.
UPDATE subscriptions SET billing_interval = 'MONTHLY' WHERE billing_interval IS NULL;

ALTER TABLE subscriptions ALTER COLUMN billing_interval SET NOT NULL;

-- 3. The plan is gone.
--
-- Dropped last, so the two statements above can be re-read against it if this migration is
-- ever inspected in a failed state. There is no tier to preserve: FREE meant "no subscription
-- row worth having" and PRO meant "the only thing we sell".
ALTER TABLE subscriptions DROP COLUMN plan;
