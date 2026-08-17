package com.unzer.shop.orders;

public enum OrderStatus {
    CREATED,
    AWAITING_PAYMENT,
    PAID,
    FULFILLING,
    SHIPPED,
    COMPLETED,
    CANCELLED,
    PAYMENT_FAILED,
    REFUNDED
}
