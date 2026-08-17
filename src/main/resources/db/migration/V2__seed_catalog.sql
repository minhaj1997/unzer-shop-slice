-- Minimal seed data so the checkout flow has something real to buy.
-- One variant is seeded with on_hand = 1 specifically to make the oversell race easy to demonstrate manually.

INSERT INTO catalog.product (id, name, description, category_id) VALUES
    ('11111111-1111-1111-1111-111111111111', 'Unzer Mug', '{"summary": "Ceramic mug, 300ml"}'::jsonb, NULL),
    ('22222222-2222-2222-2222-222222222222', 'Unzer T-Shirt', '{"summary": "100% cotton, unisex"}'::jsonb, NULL);

INSERT INTO catalog.variant (id, product_id, sku, attributes, price_minor, currency) VALUES
    ('aaaaaaaa-1111-1111-1111-111111111111', '11111111-1111-1111-1111-111111111111', 'MUG-STD', '{}'::jsonb, 1299, 'EUR'),
    ('bbbbbbbb-2222-2222-2222-222222222222', '22222222-2222-2222-2222-222222222222', 'TSHIRT-M', '{"size":"M"}'::jsonb, 2499, 'EUR'),
    ('bbbbbbbb-2222-2222-2222-222222222223', '22222222-2222-2222-2222-222222222222', 'TSHIRT-L', '{"size":"L"}'::jsonb, 2499, 'EUR');

INSERT INTO inventory.stock (variant_id, on_hand, reserved) VALUES
    ('aaaaaaaa-1111-1111-1111-111111111111', 1, 0),  -- deliberately scarce: use this SKU to demo the oversell race
    ('bbbbbbbb-2222-2222-2222-222222222222', 50, 0),
    ('bbbbbbbb-2222-2222-2222-222222222223', 50, 0);
