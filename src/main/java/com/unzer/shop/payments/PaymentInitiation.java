package com.unzer.shop.payments;

public record PaymentInitiation(
        String unzerPaymentId,
        String unzerTypeId,
        String unzerTransactionId,
        String redirectUrl,
        PaymentStatus status,
        String rawUnzerStatus
) {
}
