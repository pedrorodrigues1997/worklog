# Postman collection

Manual test collection for the TicTac backend: register → login → create an organization →
buy licences → invite somebody → watch them accept.

```
TicTac.postman_collection.json    51 requests in 8 numbered folders
TicTac.postman_environment.json   baseUrl + the two passwords
```

Import both, select **TicTac — local** as the active environment, and run the folders top to
bottom the first time. Later requests depend on variables earlier ones capture.

---

## 1. Start the backend

```bash
docker compose up -d                 # PostgreSQL 17 on :5432
set -a; source .env; set +a
./mvnw spring-boot:run
```

`JWT_SECRET` (≥32 bytes) and `JWT_ISSUER` (absolute URI) are required with no defaults — the
app refuses to start without them. Everything else has a local default.

Folders **01 → 03** work against exactly this. No Stripe account needed.

---

## 2. Emails are generated per run

A collection pre-request script sets `runId` once and derives the addresses from it:

```
owner+<runId>@tictac.test
invitee+<runId>@tictac.test
```

So you can re-run the collection without tripping `409 Email already registered`.

- **Fresh run:** delete the `runId` collection variable. New addresses next request.
- **Your own addresses:** set `ownerEmail` / `inviteeEmail` in the *environment*. Environment
  values beat collection ones, so they win.

Passwords live in the environment and must be **at least 12 characters**.

---

## 3. Configuring Stripe

Folders **04 → 06** need billing configured. In `.env`:

```bash
STRIPE_SECRET_KEY=sk_test_...
STRIPE_WEBHOOK_SECRET=whsec_...        # from `stripe listen`, step 3.3
STRIPE_PRICE_MONTHLY=price_...
STRIPE_PRICE_ANNUAL=price_...
```

Restart the app after changing these — Spring Boot does not read `.env`, Docker Compose does,
so for the app itself use `set -a; source .env; set +a`.

Without `STRIPE_SECRET_KEY` every billing endpoint answers **503**, and the collection logs a
warning telling you so. Everything non-billing keeps working.

### 3.1 Create the two prices

Stripe Dashboard → **Product catalogue** → add a product, e.g. "TicTac licence". Add two
prices to it:

| | Model | Amount | Billing period |
| --- | --- | --- | --- |
| Monthly | **Standard / per unit** | e.g. $8 | Monthly |
| Annual | **Standard / per unit** | e.g. $80 | Yearly |

Copy each `price_...` id into `.env`.

> **Per-unit, not graduated.** The quantity this backend sends already excludes the free
> included licence (`quantity = licenseCount - 1`), so a tiered price whose first unit is free
> would discount it twice.

### 3.2 Configure the Billing Portal — do this before calling `/billing/portal`

Stripe Dashboard → **Settings** → **Billing** → **Customer portal**. Turn on what you want
customers to manage (payment method, invoices, cancellation) and **click Save** at least once.

Until that configuration is saved, Stripe rejects every
`billingPortal.sessions.create` call — and the backend correctly surfaces it as:

```
502  Billing provider error
```

If the portal request 502s, this is almost always why. (In test mode the setting is saved
separately from live mode; save it in test mode.)

### 3.3 Receive webhooks locally

**Nothing is activated until Stripe confirms it.** Checkout only prepares a payment form; the
subscription — and the licences it pays for — are written by the webhook.

```bash
stripe login
stripe listen --forward-to localhost:8080/api/webhooks/stripe
```

Copy the `whsec_...` it prints into `STRIPE_WEBHOOK_SECRET` and restart the app.

Do **not** try to fire `POST /api/webhooks/stripe` from Postman. Its entire authentication is
an HMAC-SHA256 signature over the raw body, so an unsigned request is correctly rejected with
400. The collection includes that request purely to demonstrate the refusal.

### 3.4 Complete a checkout

1. Run **04 · Create checkout session — MONTHLY**.
2. Open the `checkoutUrl` it logs (also stored as a collection variable) in a browser.
3. Pay with `4242 4242 4242 4242`, any future expiry, any CVC, any postcode.
4. Watch `stripe listen` forward `customer.subscription.created`.
5. Run **05 · Get licences** — the organization now holds 5 and is billed for 4.

---

## 4. Skipping Stripe entirely

If you only want to exercise licences, invitations and membership, seed a subscription
directly and skip folders 04. Grab `organizationId` from the collection variables after
creating the organization, then:

```bash
docker compose exec -T postgres psql -U io -d io <<'SQL'
\set org 'PASTE-ORGANIZATION-ID-HERE'

-- The organization holds 5 licences: 1 free + 4 paid.
UPDATE organizations SET license_count = 5 WHERE organization_id = :'org';

INSERT INTO billing_customers (id, organization_id, provider, provider_customer_id, created_at)
VALUES (gen_random_uuid(), :'org', 'STRIPE', 'cus_manual_test', now())
ON CONFLICT DO NOTHING;

INSERT INTO subscriptions (
    id, organization_id, status, billing_interval, paid_licenses, provider,
    provider_subscription_id, provider_item_id,
    current_period_start, current_period_end,
    created_at, updated_at, cancel_at_period_end
) VALUES (
    gen_random_uuid(), :'org', 'ACTIVE', 'MONTHLY', 4, 'STRIPE',
    'sub_manual_test', 'si_manual_test',
    now(), now() + interval '30 days',
    now(), now(), false
) ON CONFLICT (organization_id) DO UPDATE
    SET status = 'ACTIVE', paid_licenses = 4;
SQL
```

The user and database both default to `io` (see `docker-compose.yml`); override with
`POSTGRES_USER` / `POSTGRES_DB` if your `.env` sets them.

With that in place, folders **05** and **06** run end to end. Requests that would call Stripe
(`PUT /licenses`, and inviting when no licence is vacant) will still fail with **502** unless
`STRIPE_SECRET_KEY` is a real test key — inviting into a *vacant* licence makes no Stripe call
at all, so that path works regardless.

---

## 5. What the interesting requests prove

| Request | What to look at |
| --- | --- |
| **03 · Invite before subscribing → 409** | The refusal lands at *invite* time. The administrator is told at the moment they decide; no dead link goes out. |
| **06 · Check licences after inviting** | `pendingInvitations` is 1 and `licensesAvailable` has dropped — an outstanding invitation **holds** a licence. |
| **06 · Licences after the join** | `billedLicenses` is unchanged. The licence was paid for when the invitation went out, not when the invitee clicked. |
| **06 · Remove the member** | `membersOccupying` drops; `licenseCount` and `billedLicenses` do not. Removal **vacates**, it never un-buys. |
| **06 · Invite again afterwards** | Reuses the vacant licence. No Stripe call, nothing charged. |
| **05 · Raise licence count** | No checkout, no redirect. Stripe charges the card on file and prorates. |
| **05 · Reduce to 1** | Cancels the subscription rather than sitting at quantity zero. |
| **08 · Soft-delete the organization** | The subscription stops renewing in the same transaction — `cancelAtPeriodEnd` goes true while the status stays ACTIVE. Afterwards every billing endpoint 404s, which is why it cannot be left for later. |
| **07 · after cancelling** | Projects, categories and timers all still work. **There are no paid features** — the only thing money buys is licences. |

Anything named `(negative)` asserts a 4xx on purpose.

---

## 6. Troubleshooting

| Symptom | Cause |
| --- | --- |
| `503 Billing not configured` | `STRIPE_SECRET_KEY` is blank. Normal locally; set it and restart. |
| `502 Billing provider error` on `/billing/portal` | Portal configuration never saved in the Stripe Dashboard — see 3.2. |
| `502` on `PUT /licenses` | Stripe rejected the call, or the key is a placeholder. Check the `stripe listen` output and the app log. |
| `409 No subscription yet` | Nothing has been bought. Complete a checkout (3.4) or seed one (§4). |
| `409 No subscription to add a licence to` on invite | Same cause, seen from the invitation side: the included licence is occupied and there is no card to buy another. |
| `401` on everything after ~15 minutes | Access tokens live 15 minutes. Re-run **Login owner**. |
| `401` on `POST /auth/refresh` | The refresh token was already rotated. Rotating one twice is treated as theft and revokes every session — re-run **Login owner**. |
| `409 Email already registered` | The `runId` was reused. Delete the `runId` collection variable. |
| `404` on an organization you own | Wrong `organizationId`, or it was soft-deleted in folder 08. |
