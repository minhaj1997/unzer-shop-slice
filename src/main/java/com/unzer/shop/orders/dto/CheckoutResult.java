package com.unzer.shop.orders.dto;

import java.util.UUID;

public record CheckoutResult(
        boolean success,
        UUID orderId,
        String status,
        String redirectUrl,
        String failureReason
) {
    public static CheckoutResult ok(UUID orderId, String status, String redirectUrl) {
        return new CheckoutResult(true, orderId, status, redirectUrl, null);
    }

    public static CheckoutResult outOfStock(UUID orderId, String status, String sku) {
        return new CheckoutResult(false, orderId, status, null, "out_of_stock: " + sku);
    }

    public static CheckoutResult paymentFailed(UUID orderId, String status, String reason) {
        return new CheckoutResult(false, orderId, status, null, "payment_initiation_failed: " + reason);
    }
}
