package com.unzer.shop.orders.dto;

import java.util.UUID;

public record RefundResult(
        boolean success,
        UUID orderId,
        String status,
        long refundedMinor,
        long totalRefundedMinor,
        boolean fullyRefunded,
        String failureReason
) {
    public static RefundResult ok(UUID orderId, String status, long refundedMinor,
                                   long totalRefundedMinor, boolean fullyRefunded) {
        return new RefundResult(true, orderId, status, refundedMinor, totalRefundedMinor, fullyRefunded, null);
    }

    public static RefundResult rejected(UUID orderId, String status, String reason) {
        return new RefundResult(false, orderId, status, 0, 0, false, reason);
    }
}
