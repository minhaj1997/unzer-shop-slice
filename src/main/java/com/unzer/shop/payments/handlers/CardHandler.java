package com.unzer.shop.payments.handlers;

import com.unzer.payment.Authorization;
import com.unzer.payment.Cancel;
import com.unzer.payment.Unzer;
import com.unzer.payment.PaymentException;
import com.unzer.payment.communication.HttpCommunicationException;
import com.unzer.shop.payments.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Currency;

@Component
@RequiredArgsConstructor
@Slf4j
public class CardHandler implements PaymentMethodHandler {

    private final Unzer unzer;

    @Override
    public PaymentMethod method() {
        return PaymentMethod.CARD;
    }

    @Override
    public PaymentInitiation initiate(InitiateContext ctx) {
        if (ctx.clientTypeId() == null || ctx.clientTypeId().isBlank()) {
            throw new IllegalArgumentException(
                    "Card payments require a typeId obtained client-side from Unzer UI Components tokenization");
        }
        try {
            BigDecimal amount = BigDecimal.valueOf(ctx.amountMinor()).movePointLeft(2);

            Authorization auth = unzer.authorize(
                    amount,
                    Currency.getInstance(ctx.currency()),
                    ctx.clientTypeId(),
                    new URL(ctx.returnUrl()));

            Authorization.Status rawStatus = auth.getStatus();
            PaymentStatus status = mapStatus(rawStatus);

            return new PaymentInitiation(
                    auth.getPaymentId(),
                    ctx.clientTypeId(),
                    auth.getId(),
                    null,
                    status,
                    String.valueOf(rawStatus));

        } catch (MalformedURLException e) {
            throw new IllegalStateException("Invalid configured return URL: " + ctx.returnUrl(), e);
        } catch (HttpCommunicationException | PaymentException e) {
            throw new PaymentInitiationException("Card authorization failed for order " + ctx.orderId(), e);
        }
    }

    @Override
    public void capture(Payment payment) {
        try {
            unzer.chargeAuthorization(payment.getUnzerPaymentId());
            log.info("Captured card authorization for payment {}", payment.getId());
        } catch (HttpCommunicationException | PaymentException e) {
            throw new PaymentCaptureException("Card capture failed for payment " + payment.getId(), e);
        }
    }

    @Override
    public String refund(Payment payment, long amountMinor, String reason) {
        try {
            Cancel cancel = (amountMinor == payment.getAmountMinor())
                    ? unzer.cancelAuthorization(payment.getUnzerPaymentId())
                    : unzer.cancelAuthorization(payment.getUnzerPaymentId(),
                            BigDecimal.valueOf(amountMinor).movePointLeft(2));
            log.info("Voided/refunded {} minor units of card authorization for payment {}", amountMinor, payment.getId());
            return cancel.getId();
        } catch (HttpCommunicationException | PaymentException e) {
            throw new PaymentCaptureException("Card refund failed for payment " + payment.getId(), e);
        }
    }

    private PaymentStatus mapStatus(Authorization.Status status) {
        return switch (status) {
            case SUCCESS -> PaymentStatus.AUTHORIZED;
            case PENDING, RESUMED -> PaymentStatus.PENDING;
            case ERROR -> PaymentStatus.FAILED;
        };
    }
}
