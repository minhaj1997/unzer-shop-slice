package com.unzer.shop.payments;

import com.unzer.shop.orders.OrderStatus;

import java.util.UUID;

public interface PaymentGateway {

    PaymentInitiation initiate(UUID orderId, PaymentMethod method, long amountMinor, String currency,
                                String clientTypeId);

    void capture(UUID orderId);

    RefundOutcome refund(UUID orderId, long amountMinor, String reason);

    ReconcileOutcome reconcile(String unzerPaymentId);

    record ReconcileOutcome(UUID orderId, PaymentStatus status, boolean changed) {
    }

    record RefundOutcome(OrderStatus orderStatus, boolean fullyRefunded, long totalRefundedMinor) {
    }
}
