# Unzer Shop — Vertical Slice

Checkout → payment → order-confirmation, integrated against the real Unzer sandbox. This is the
code deliverable for the take-home assignment; the full-shop design is in
[`docs/architecture.md`](docs/architecture.md). This README covers only what's needed to run and
poke at *this* slice.

## What's real vs. stubbed

| Piece | Status |
|---|---|
| Checkout → order creation → stock reservation | **Real** — atomic conditional `UPDATE`, one local transaction |
| Credit Card via Unzer (authorize at checkout, capture at shipment) | **Real**, against the Unzer sandbox |
| Wero via Unzer (charge + redirect) | **Real**, against the Unzer sandbox |
| Open Banking | **Stubbed** — designed behind the same interface, throws a clear `UnsupportedOperationException`. See `OpenBankingHandler` |
| Webhook receiver (`/webhooks/unzer`) | **Real** — dedup, re-fetch-don't-trust, backstop poller |
| Order state machine | **Real** — guard-checked, unit tested |
| Oversell prevention | **Real** — proven under genuine concurrency, see `InventoryConcurrencyTest` |
| Payment → order transition wiring | **Real** — a direct in-process call from `PaymentService` to `OrderService`/`InventoryService`, matching docs/architecture.md's choice for the current system (an outbox+queue is called out there as a later step, only once a module becomes a genuinely separate deployed service) |
| Customer accounts / auth (JWT, admin role) | **Not implemented** — guest checkout only |
| Catalog browsing, cart persistence | **Minimal** — checkout takes line items directly rather than a persisted cart; seeded SKUs are documented below |
| Admin UI, shipping/fulfilment | **Not implemented** — out of scope per the assignment brief |

## Prerequisites

- Java 21
- Maven
- Docker (for local Postgres) — or point `DB_URL`/`DB_USER`/`DB_PASSWORD` at any Postgres 14+ instance
- An Unzer sandbox private key (provided at interview time per the assignment; see §8 of the brief)
- [ngrok](https://ngrok.com/) or [localtunnel](https://github.com/localtunnel/localtunnel), for
  receiving webhooks locally

## Setup

```bash
# 1. Copy the env template and fill in your sandbox key
cp .env.example .env
# edit .env: set UNZER_PRIVATE_KEY

# 2. Start Postgres
docker compose up -d

# 3. Run
mvn spring-boot:run
```

`.env` is picked up automatically at startup (`spring.config.import` in `application.yml`) — no
need to export the variables into your shell.

Flyway runs automatically on startup and creates the full schema-per-module layout plus a small
seed catalog (see `V2__seed_catalog.sql`) — including one deliberately scarce SKU (`on_hand = 1`)
for demonstrating the oversell race by hand if you want to see it outside the test suite.

### Registering the webhook (local dev)

Unzer needs a public URL to send notifications to. In a second terminal:

```bash
ngrok http 8080
# note the https://xxxx.ngrok-free.app URL it prints
```

Then register it against your sandbox key (one-time, or whenever the ngrok URL changes):

```bash
curl -X POST https://api.unzer.com/v1/webhooks \
  -H "Authorization: Basic $(echo -n 'YOUR_PRIVATE_KEY:' | base64)" \
  -H "Content-Type: application/json" \
  -d '{
    "url": "https://xxxx.ngrok-free.app/webhooks/unzer",
    "event": "payment"
  }'
```

## Walkthrough

### 1. Seeded catalog

No catalog API in this slice — the seed data (`V2__seed_catalog.sql`) uses fixed UUIDs, so they're
just documented here directly:

| SKU | variantId | Stock |
|---|---|---|
| MUG-STD | `aaaaaaaa-1111-1111-1111-111111111111` | 1 (deliberately scarce — use this to demo the oversell race) |
| TSHIRT-M | `bbbbbbbb-2222-2222-2222-222222222222` | 50 |
| TSHIRT-L | `bbbbbbbb-2222-2222-2222-222222222223` | 50 |

### 2. Checkout with Wero (easiest to test end-to-end — no client-side tokenization needed)

```bash
curl -X POST http://localhost:8080/api/checkout \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: demo-$(uuidgen)" \
  -d '{
    "guestEmail": "test@example.com",
    "method": "WERO",
    "items": [{"variantId": "bbbbbbbb-2222-2222-2222-222222222222", "qty": 1}]
  }'
```

The response includes a `redirectUrl` — open it in a browser to complete the Wero sandbox flow.
On return, Unzer redirects to `UNZER_RETURN_URL`, which this slice serves at
`GET /api/orders/return?...` — but note the `id` from the checkout response and use
`GET /api/orders/{id}/return` directly if you'd rather not build a browser-facing redirect target.

### 3. Checkout with Credit Card

Card requires a `typeId` from Unzer UI Components tokenization — this slice doesn't include a
browser-side checkout page, so you'll need to tokenize a
[test card](https://docs.unzer.com/reference/test-cards/) yourself (e.g. via Unzer's hosted UI
Components demo, or a minimal HTML page loading `unzer.js`) to get a `s-crd-...` typeId, then:

```bash
curl -X POST http://localhost:8080/api/checkout \
  -H "Content-Type: application/json" \
  -d '{
    "guestEmail": "test@example.com",
    "method": "CARD",
    "clientTypeId": "s-crd-xxxxxxxxxxxx",
    "items": [{"variantId": "bbbbbbbb-2222-2222-2222-222222222223", "qty": 1}]
  }'
```

### 4. Check order status

```bash
curl http://localhost:8080/api/orders/{id}
```

Returns the order, its line items, and its full status-transition history.

### 5. Watch the webhook arrive

Once the payment resolves on Unzer's side, their webhook hits `/webhooks/unzer`, which reconciles
and immediately flips the order to `PAID`. Tail the app logs — `PaymentService` logs at INFO for
both the reconcile and the resulting transition.

### 6. Try the oversell race

```bash
for i in $(seq 1 10); do
  curl -s -X POST http://localhost:8080/api/checkout \
    -H "Content-Type: application/json" \
    -d '{"guestEmail":"racer'"$i"'@example.com","method":"WERO","items":[{"variantId":"aaaaaaaa-1111-1111-1111-111111111111","qty":1}]}' &
done
wait
```

Exactly one of the ten should come back `200` (AWAITING_PAYMENT); the rest `409` (CANCELLED,
`out_of_stock`). This is the manual version of what `InventoryConcurrencyTest` proves
automatically under harsher, more deterministic concurrency.

## Running the tests

```bash
mvn test
```

- **`InventoryConcurrencyTest`** — spins up real Postgres via Testcontainers and hammers
  `InventoryService.reserve()` with 25 concurrent threads racing for the last unit of stock;
  asserts exactly one wins.
- **`OrderStateMachineTest`** — pure unit test proving every legal and illegal transition, and
  specifically that applying the same transition twice is a no-op — the property that makes
  duplicate webhooks harmless.

**Not included**: a full end-to-end integration test that mocks the Unzer SDK boundary and drives
checkout → webhook → reconcile → `PAID` in one test, and an automated test for the "reservation
expired, then payment succeeded" refund path (implemented in `PaymentService.onPaid`, not yet
exercised by a test). Both are natural next steps.

## Known limitations

- **Idempotency-Key race**: two *concurrent* requests with the same key can still both create an
  order if they race past the initial lookup before either has written its key row. The documented
  use case — a client retrying after a timeout — is handled correctly; true concurrent duplicate
  submission with the same key is not fully closed.
- **Re-reservation on late payment** (the "reservation expires, then payment succeeds" case):
  `PaymentService.onPaid` goes straight to refund-and-cancel rather than attempting a fresh
  reservation first, since a real re-reservation attempt needs the order's line items, which live
  in a different module than this method.
- **Open Banking** is a stub, as the assignment explicitly allows.
