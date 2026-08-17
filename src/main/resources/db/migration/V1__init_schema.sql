-- Schema-per-module layout. See architecture.md §2 (System Decomposition) and §3 (Domain & Data Model).
-- All money columns are integer minor units (cents) + a currency code — never floating point.

CREATE SCHEMA IF NOT EXISTS customers;
CREATE SCHEMA IF NOT EXISTS catalog;
CREATE SCHEMA IF NOT EXISTS cart;
CREATE SCHEMA IF NOT EXISTS inventory;
CREATE SCHEMA IF NOT EXISTS orders;
CREATE SCHEMA IF NOT EXISTS payments;
CREATE SCHEMA IF NOT EXISTS shared; -- outbox, idempotency, webhook dedup: cross-cutting, not owned by one module

-- ============================================================= customers
-- Guest checkout is the only path implemented in this slice; this table exists for completeness
-- of the schema but is not populated by the checkout flow itself.
CREATE TABLE customers.customer (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           TEXT NOT NULL UNIQUE,
    password_hash   TEXT,
    role            TEXT NOT NULL DEFAULT 'CUSTOMER' CHECK (role IN ('CUSTOMER', 'ADMIN')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============================================================= catalog
CREATE TABLE catalog.product (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT NOT NULL,
    description     JSONB,
    category_id     UUID
);

CREATE TABLE catalog.variant (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id      UUID NOT NULL REFERENCES catalog.product(id),
    sku             TEXT NOT NULL UNIQUE,
    attributes      JSONB NOT NULL DEFAULT '{}'::jsonb,
    price_minor     BIGINT NOT NULL CHECK (price_minor >= 0),
    currency        VARCHAR(3) NOT NULL DEFAULT 'EUR'
);

CREATE INDEX idx_variant_product ON catalog.variant(product_id);

-- ============================================================= inventory
-- The oversell-prevention mechanism (architecture.md §5) lives entirely in this one table:
-- reservation is a single atomic UPDATE ... WHERE on_hand - reserved >= qty.
CREATE TABLE inventory.stock (
    variant_id      UUID PRIMARY KEY,     -- logical FK to catalog.variant(id); no DB constraint, see architecture.md section 2
    on_hand         INT NOT NULL CHECK (on_hand >= 0),
    reserved        INT NOT NULL DEFAULT 0 CHECK (reserved >= 0),
    CHECK (reserved <= on_hand)
);

CREATE TABLE inventory.reservation (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID NOT NULL,
    variant_id      UUID NOT NULL,        -- logical FK to catalog.variant(id); no DB constraint, see architecture.md section 2
    qty             INT NOT NULL CHECK (qty > 0),
    expires_at      TIMESTAMPTZ NOT NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RELEASED', 'COMMITTED'))
);

CREATE INDEX idx_reservation_order ON inventory.reservation(order_id);
-- Partial index powering the expiry sweep: only ever scans live reservations.
CREATE INDEX idx_reservation_expiry ON inventory.reservation(expires_at) WHERE status = 'ACTIVE';

-- ============================================================= cart
CREATE TABLE cart.cart (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_id     UUID,                  -- logical FK to customers.customer(id); no DB constraint, see architecture.md section 2
    token           TEXT NOT NULL UNIQUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE cart.cart_item (
    cart_id         UUID NOT NULL REFERENCES cart.cart(id) ON DELETE CASCADE,
    variant_id      UUID NOT NULL,        -- logical FK to catalog.variant(id); no DB constraint, see architecture.md section 2
    qty             INT NOT NULL CHECK (qty > 0),
    PRIMARY KEY (cart_id, variant_id)
);

-- ============================================================= orders
-- Table is named customer_order, not "order" — ORDER is a reserved word in Postgres and would
-- need quoting everywhere (DDL, every raw query, every JPA @Table) if used literally.
CREATE TABLE orders.customer_order (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    customer_ref    UUID,
    guest_email     TEXT,
    status          TEXT NOT NULL,
    total_minor     BIGINT NOT NULL CHECK (total_minor >= 0),
    currency        VARCHAR(3) NOT NULL DEFAULT 'EUR',
    version         INT NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_order_customer_created ON orders.customer_order(customer_ref, created_at);

CREATE TABLE orders.order_item (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL REFERENCES orders.customer_order(id) ON DELETE CASCADE,
    variant_id          UUID NOT NULL,
    sku                 TEXT NOT NULL,
    name                TEXT NOT NULL,
    unit_price_minor    BIGINT NOT NULL,
    qty                 INT NOT NULL CHECK (qty > 0)
);

CREATE INDEX idx_order_item_order ON orders.order_item(order_id);

CREATE TABLE orders.order_status_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        UUID NOT NULL REFERENCES orders.customer_order(id) ON DELETE CASCADE,
    from_status     TEXT,
    to_status       TEXT NOT NULL,
    cause           TEXT,
    occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_status_history_order ON orders.order_status_history(order_id);

-- ============================================================= payments
-- Maps our domain to Unzer's resource/transaction model: one order -> one payment
-- -> one Unzer paymentId -> n transactions (authorize/charge/refund).
CREATE TABLE payments.payment (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id            UUID NOT NULL UNIQUE, -- logical FK to orders.customer_order(id); no DB constraint, see architecture.md section 2
    method              TEXT NOT NULL CHECK (method IN ('CARD', 'WERO', 'OPEN_BANKING')),
    status              TEXT NOT NULL,
    unzer_payment_id    TEXT UNIQUE, -- e.g. "s-pay-1"; null until Unzer responds
    unzer_type_id       TEXT,        -- e.g. "s-crd-xxx" or "s-wro-xxx"
    amount_minor        BIGINT NOT NULL,
    currency            VARCHAR(3) NOT NULL DEFAULT 'EUR',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE payments.payment_transaction (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID NOT NULL REFERENCES payments.payment(id) ON DELETE CASCADE,
    kind            TEXT NOT NULL CHECK (kind IN ('AUTHORIZE', 'CHARGE', 'REFUND')),
    unzer_tx_id     TEXT,
    status          TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_tx_payment ON payments.payment_transaction(payment_id);

CREATE TABLE payments.refund (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID NOT NULL REFERENCES payments.payment(id),
    amount_minor    BIGINT NOT NULL,
    reason          TEXT,
    unzer_tx_id     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- No CHECK constraint enforcing sum(amount_minor) <= payment.amount_minor here: that requires a
-- running sum across sibling rows, which a single-row CHECK can't express. Enforced in code
-- instead (PaymentService.issueRefund), against the existing rows, before ever calling Unzer.

-- ============================================================= shared (cross-cutting)
-- Note: no outbox table here. architecture.md describes an outbox + SQS relay for the full
-- production system (§5, §7), but this slice drives payment -> order effects with a direct
-- synchronous call instead (see PaymentService) since there's no real async boundary inside one
-- JVM process. An earlier draft of this slice built the table anyway and never used it; removed
-- rather than left as dead schema.
CREATE TABLE shared.processed_webhook (
    fingerprint     TEXT PRIMARY KEY,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE shared.idempotency_key (
    key             TEXT PRIMARY KEY,
    order_id        UUID NOT NULL,
    response_body   JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
