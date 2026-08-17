# Project context: Unzer take-home assignment

Two deliverables for an Unzer take-home: (1) `architecture.md` — a full e-commerce backend design
(catalog, cart, checkout, orders, inventory, customers) with Unzer as the payment subsystem
(Credit Card 3-DS, Wero, Open Banking); (2) this repo — **one deep vertical slice** of that design:
checkout → payment → order-confirmation, in Java/Spring Boot, against the real Unzer sandbox.

The person has now **compiled and packaged this successfully** (`mvn package` produced a working
jar). It wasn't always that way: everything below was originally written by an earlier Claude
instance with **no Maven, no Maven Central access, and no way to actually run it**, then fixed
against a real compiler in a later pass — six additional bugs turned up during that first real
build, all catalogued below. The gap between "designed carefully" and "verified by running it"
used to matter a lot here; it matters less now, but the two remaining unverified items at the
bottom are still worth treating with the same suspicion as everything else was.

## Architecture (the design, only briefly — see architecture.md for the full doc)

- **Modular monolith**, one Spring Boot deployable, schema-per-module in one Postgres DB
  (`customers`, `catalog`, `cart`, `inventory`, `orders`, `payments`, `shared`).
- **Consistency**: reserve+create-order is one local ACID transaction (same DB, no saga needed).
  Payment (external system) is a separate step outside that transaction.
- **Oversell prevention**: one atomic conditional `UPDATE ... WHERE on_hand - reserved >= qty`.
  No read-then-write gap, no app-level locking.
- **Order lifecycle**: `CREATED → AWAITING_PAYMENT → PAID → FULFILLING → SHIPPED → COMPLETED`,
  plus `CANCELLED`, `PAYMENT_FAILED`, `REFUNDED`. Guard-checked state machine — illegal
  transitions rejected, repeated transitions are no-ops (this is what makes duplicate webhooks safe).
- **Card**: authorize at checkout, capture at `FULFILLING → SHIPPED`. **Wero**: charge-only
  (Unzer doesn't offer authorize for it) — settles immediately, redirect-based.
- **Webhook security**: Unzer notifications carry **no signature** (confirmed against their real
  docs — an earlier draft of architecture.md wrongly claimed signature verification and was
  corrected). The actual control: never trust the event name or the payload's `retrieveUrl`;
  always re-fetch state by `paymentId` through your own pinned client.

## What's actually in this repo (the slice)

```
catalog/     Variant, VariantRepository                          — minimal, no API, no Product entity
inventory/   Stock, Reservation(+Status), InventoryService       — the atomic UPDATE lives here
orders/      Order, OrderItem, OrderStatusHistory, OrderStatus,
             OrderStateMachine, OrderService, CheckoutTransaction,
             CheckoutService, OrderController, IdempotencyKey,
             CheckoutAbandonmentSweepJob, dto/*
payments/    Payment, PaymentTransaction, PaymentGateway,
             PaymentMethodHandler, PaymentService, WebhookController,
             ReconciliationPollerJob, ProcessedWebhook,
             handlers/{Card,Wero,OpenBanking}Handler
config/      UnzerProperties, UnzerClientConfig
```

**Deliberate cuts** (see README.md "What's real vs. stubbed" for the full table):
- No auth/JWT — guest checkout only.
- No cart persistence — checkout takes line items directly in the request body.
- No catalog API or `Product` entity — checkout resolves `Variant` by ID directly; catalog
  discovery is just documented in the README (fixed seed UUIDs).
- Open Banking is a stub (`OpenBankingHandler` throws `UnsupportedOperationException`) — the
  brief explicitly allows this; only Card + Wero are real.
- **No outbox/message-relay pattern in the code** — and, since the person's update to
  `docs/architecture.md`, that's no longer a simplification versus the design doc, it's what the
  design doc itself now calls for. `PaymentService.reconcile()` calls `OrderService`/
  `InventoryService` directly, matching architecture.md §7/§9, which now names an outbox+queue as
  a later step to take only once a module (e.g. `inventory`) is actually split into its own
  deployed service — not something to build ahead of that need. An earlier draft of this slice
  had actually built the outbox table + in-process relay + event listener (7 files) purely to
  mirror an earlier version of the diagram; that's what was removed. If you're asked to add real
  async messaging (SQS, etc.), that's the point this pattern would come back for real, and
  architecture.md already describes what it should look like when it does.

## Real bugs already found and fixed (context for how much to trust what's here)

- Spring **self-invocation bug**: an early draft had `CheckoutService` call an `@Transactional`
  method on `this` — that bypasses Spring's proxy silently, which would have broken the
  reserve+create-order atomicity guarantee with no error at all. Fixed by extracting that logic
  into its own bean, `CheckoutTransaction`.
- `order` is a **reserved word in Postgres** — the table is `customer_order`, not `order`.
- Binding a plain `String` to a `jsonb` column in Hibernate 6 needs `@JdbcTypeCode(SqlTypes.JSON)`
  — compiles fine without it, fails at insert time.
- `flyway-database-postgresql` doesn't exist for Flyway 9.x, which is what Spring Boot 3.2.5
  manages — that split only happened at Flyway 10 (Spring Boot 3.3+). Parent bumped to `3.3.13`.
- The Unzer SDK Maven version was guessed (`6.5.0.0`) and wrong. Corrected to the real current
  release, **5.9.0**, after finding `github.com/unzerdev/java-sdk` directly.
- `WeroHandler`'s refund call used a `cancelCharge(paymentId)` one-arg overload that doesn't
  exist. Fixed to the confirmed `cancelCharge(paymentId, amount)` two-arg overload.
- `ReservationExpiredEvent` was published but had **no listener** — reservations would release
  stock on expiry but never actually cancel the order. Fixed while removing the outbox pattern
  (the sweep is now `CheckoutAbandonmentSweepJob`, a direct call, no event).

**Then it actually got compiled against the real jar for the first time, which caught six more —
every one of them exactly where this file's "still genuinely unverified" section had flagged
suspicion:**

- `com.unzer.payment.exceptions.{HttpCommunicationException,PaymentException}` doesn't exist —
  there's no `exceptions` subpackage at all. Real paths: `com.unzer.payment.PaymentException`
  and `com.unzer.payment.communication.HttpCommunicationException`.
- `.getTransactionId()` doesn't exist on `Authorization`/`Charge` — it's just `.getId()`
  (inherited from their shared `BaseTransaction` superclass).
- `Authorization.Status`/`Charge.Status` have a `RESUMED` case neither switch accounted for —
  this alone would have failed to compile (arrow-style `switch` over an enum must be exhaustive).
- The real getter for payment state is `getPaymentState()`, returning a
  `com.unzer.payment.BasePayment.State` **enum** — not the guessed `getStateName(): String`.
  `PaymentService.reconcile()` now calls `.name()` on it before the existing lowercase-string
  matching in `mapState()`.
- `charge.getRedirectUrl()` returns a `URL`, not a `String` — needed an explicit
  `.toString()` with a null-check.
- `EnumSet.of()` called with **zero** arguments (for terminal states with no outgoing
  transitions) doesn't compile — Java can't infer the generic type from no elements. Fixed to
  `EnumSet.noneOf(OrderStatus.class)`.

Two more, unrelated to the SDK: `currency CHAR(3)` columns almost certainly failed Hibernate's
`ddl-auto: validate` at startup (fixed to `VARCHAR(3)`); and `spring.config.import:
"optional:file:.env[.properties]"` was added so `.env` loads automatically instead of needing to
be exported into the shell first.

## Verified against the real SDK now — no longer just inferred

- `getPaymentState()` / `BasePayment.State` — confirmed (see above).
- Exception package paths — confirmed (see above).
- `.getId()` in place of the guessed `.getTransactionId()` — confirmed.

## Local customizations (yours, not mistakes — don't revert these)

- `pom.xml` has an explicit `maven-compiler-plugin` → `annotationProcessorPaths` entry for Lombok,
  pinned to `${lombok.version}` (inherited from the Spring Boot parent POM, not redeclared here).
  This was added locally, presumably because Lombok's annotation processing wasn't firing
  correctly without it in this environment/Maven version — a known, environment-dependent gotcha.
  Legitimate fix, kept as-is.
- `docker-compose.yml` maps Postgres to host port `5433`, not the default `5432` — almost
  certainly to avoid clashing with a Postgres already running locally on `5432`. Kept as-is; if
  you run against this compose file, point `DB_URL` at `5433` (or override to whatever you set).

## Two more found by inspection (schema vs. stated design, and dead schema)

- **Five foreign keys crossed module boundaries in `V1__init_schema.sql`**, directly contradicting
  architecture.md §2's stated rule ("no foreign keys cross module boundaries — one module refers
  to another only by ID"): `inventory.stock.variant_id`, `inventory.reservation.variant_id`,
  `cart.cart.customer_id`, `cart.cart_item.variant_id`, and `payments.payment.order_id` all had
  `REFERENCES` clauses pointing into another module's schema. Removed the constraints (kept the
  plain UUID columns — the logical reference is still there, just not DB-enforced), since a real
  FK across schemas would block ever splitting those modules into separate databases later, which
  is the entire point of keeping them in separate schemas. Same-module FKs (e.g.
  `catalog.variant.product_id → catalog.product`) are fine and were left alone.
- **`shared.outbox` was dead schema** — a table nothing in the Java code writes to or reads from
  anymore, left over from before the outbox pattern was removed from this slice (see the section
  above). Removed from the migration. `shared.processed_webhook` and `shared.idempotency_key` are
  both genuinely used and were left in place.

## Still worth double-checking if you touch these areas

- Whether `cancelCharge(String, BigDecimal)` (used in `WeroHandler.refundFull`) generalizes past
  the Paylater use case the SDK's CHANGELOG entry that introduced it specifically mentions.
- The exact Java package for the `Wero` payment-type class — corroborated by sibling classes
  confirmed in `com.unzer.payment.paymenttypes`, but Wero itself wasn't named there directly.
  (If this compiled cleanly, it's confirmed too — check before assuming otherwise.)

## Tests

- `InventoryConcurrencyTest` (Testcontainers, real Postgres): races 25 threads for the last unit
  of stock, asserts exactly one wins. This is the proof for the oversell mechanism.
- `OrderStateMachineTest` (pure unit test): every legal/illegal transition, plus the specific
  "applying the same transition twice is a no-op" property duplicate webhooks depend on.

Not included: a full checkout→webhook→PAID integration test against a mocked Unzer client, and
an automated test for the "reservation expired, then payment succeeded" refund path (it's
implemented in `PaymentService.onPaid`, just not exercised by a test). Both are natural next steps.

## Where things stand right now

**Compiles and packages successfully** (`mvn package` produced a working jar) — confirmed by the
person, and the six fixes above were found by that real build, not guessed. Next likely work:
exercising the real Unzer sandbox flow end-to-end (Card + Wero), running the test suite for real,
and double-checking the two remaining items in "still worth double-checking" above.
