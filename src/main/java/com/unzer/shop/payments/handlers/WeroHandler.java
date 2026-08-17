package com.unzer.shop.payments.handlers;

import com.unzer.payment.Cancel;
import com.unzer.payment.Charge;
import com.unzer.payment.Unzer;
import com.unzer.payment.PaymentException;
import com.unzer.payment.communication.HttpCommunicationException;
import com.unzer.payment.paymenttypes.Wero;
import com.unzer.shop.payments.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Currency;

@Component
@RequiredArgsConstructor
public class WeroHandler implements PaymentMethodHandler {

    private final Unzer unzer;

    @Override
    public PaymentMethod method() {
        return PaymentMethod.WERO;
    }

    @Override
    public PaymentInitiation initiate(InitiateContext ctx) {
        try {
            Wero wero = new Wero();
            wero = unzer.createPaymentType(wero);

            BigDecimal amount = BigDecimal.valueOf(ctx.amountMinor()).movePointLeft(2);

            Charge charge = unzer.charge(
                    amount,
                    Currency.getInstance(ctx.currency()),
                    wero.getId(),
                    new URL(ctx.returnUrl()));

            Charge.Status rawStatus = charge.getStatus();

            return new PaymentInitiation(
                    charge.getPaymentId(),
                    wero.getId(),
                    charge.getId(),
                    charge.getRedirectUrl() == null ? null : charge.getRedirectUrl().toString(),
                    mapStatus(rawStatus),
                    String.valueOf(rawStatus));

        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid configured return URL: " + ctx.returnUrl(), e);
        } catch (HttpCommunicationException | PaymentException e) {
            throw new PaymentInitiationException("Wero charge failed for order " + ctx.orderId(), e);
        }
    }

    private PaymentStatus mapStatus(Charge.Status status) {
        return switch (status) {
            case SUCCESS -> PaymentStatus.PAID;
            case PENDING, RESUMED -> PaymentStatus.PENDING;
            case ERROR -> PaymentStatus.FAILED;
        };
    }

    @Override
    public String refund(Payment payment, long amountMinor, String reason) {
        try {
            BigDecimal amount = BigDecimal.valueOf(amountMinor).movePointLeft(2);
            Cancel cancel = unzer.cancelCharge(payment.getUnzerPaymentId(), amount);
            return cancel.getId();
        } catch (HttpCommunicationException | PaymentException e) {
            throw new PaymentCaptureException("Wero refund failed for payment " + payment.getId(), e);
        }
    }
}
