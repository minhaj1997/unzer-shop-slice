package com.unzer.shop.payments;

import java.util.UUID;

public record InitiateContext(
        UUID orderId,
        long amountMinor,
        String currency,
        String returnUrl,
        String clientTypeId
) {
}
