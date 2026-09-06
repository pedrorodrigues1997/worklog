-- Licenses belong to the ORGANIZATION, not to its subscription.
--
-- This is the column that makes "a license is not a member" representable. Before it, the
-- number of paid seats was *derived* from the member count, so the two could never disagree
-- and a vacant license had nowhere to exist: removing somebody silently reduced what the
-- organization held.
--
-- It lives on `organizations` rather than on `subscriptions` for two reasons. Every
-- organization holds at least one license (the free, included one) from the moment it is
-- created, and most never buy a subscription at all - so a subscription-owned column would be
-- null exactly where the count is still meaningful. And when a subscription ends, the
-- organization keeps its members and keeps this record of what it held; nobody is turned out
-- because a card expired.
--
-- The Stripe quantity is NOT this number. One license is included free, so Stripe is billed
-- for `license_count - 1` - see OrganizationLicenses.

ALTER TABLE organizations
    ADD COLUMN license_count INTEGER NOT NULL DEFAULT 1;

-- Backfill, preserving what every existing organization already has.
--
-- The old model had no license count, so it has to be reconstructed from the two things that
-- did exist: how many members the organization has (each occupied a seat) and what Stripe was
-- last told (`seat_quantity`, which was paid seats, so one fewer than the licenses it
-- represents). Taking the greatest of the two - never less than 1 - means no organization
-- comes out of this migration holding fewer licenses than it has people, and none loses a seat
-- it was already paying for.
UPDATE organizations o
SET license_count = GREATEST(
    1,
    (SELECT COUNT(*) FROM organization_members m WHERE m.organization_id = o.organization_id),
    COALESCE((SELECT s.seat_quantity + 1 FROM subscriptions s WHERE s.organization_id = o.organization_id), 1)
);

-- The included license always exists, so the count can never reach zero. This is also what
-- makes `paid_licenses = license_count - 1` safe to compute without a floor.
ALTER TABLE organizations
    ADD CONSTRAINT ck_organizations_license_count_positive CHECK (license_count >= 1);
