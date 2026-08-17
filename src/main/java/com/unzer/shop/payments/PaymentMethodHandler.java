package com.unzer.shop.payments;

public interface PaymentMethodHandler {

    PaymentMethod method();

    PaymentInitiation initiate(InitiateContext ctx);

    default void capture(Payment payment) {
    }

    default String refund(Payment payment, long amountMinor, String reason) {
        return null;
    }
}
