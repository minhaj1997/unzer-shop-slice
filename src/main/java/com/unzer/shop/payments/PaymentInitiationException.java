package com.unzer.shop.payments;

public class PaymentInitiationException extends RuntimeException {
    public PaymentInitiationException(String message, Throwable cause) {
        super(message, cause);
    }
}
