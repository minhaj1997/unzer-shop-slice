package com.unzer.shop.payments;

public class PaymentCaptureException extends RuntimeException {
    public PaymentCaptureException(String message, Throwable cause) {
        super(message, cause);
    }
}
