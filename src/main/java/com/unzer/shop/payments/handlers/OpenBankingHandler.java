package com.unzer.shop.payments.handlers;

import com.unzer.shop.payments.InitiateContext;
import com.unzer.shop.payments.PaymentInitiation;
import com.unzer.shop.payments.PaymentMethod;
import com.unzer.shop.payments.PaymentMethodHandler;
import org.springframework.stereotype.Component;

@Component
public class OpenBankingHandler implements PaymentMethodHandler {

    @Override
    public PaymentMethod method() {
        return PaymentMethod.OPEN_BANKING;
    }

    @Override
    public PaymentInitiation initiate(InitiateContext ctx) {
        throw new UnsupportedOperationException(
                "Open Banking is designed but stubbed in this vertical slice — see README.md " +
                "'What's real vs. stubbed' and the Javadoc on " + getClass().getName() + ".");
    }
}
