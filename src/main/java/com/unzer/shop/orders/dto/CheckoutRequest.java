package com.unzer.shop.orders.dto;

import com.unzer.shop.payments.PaymentMethod;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.Valid;

import java.util.List;
import java.util.UUID;

public record CheckoutRequest(
        @Email String guestEmail,
        @NotNull PaymentMethod method,
        String clientTypeId,
        @NotEmpty @Valid List<Line> items
) {
    public record Line(@NotNull UUID variantId, int qty) {
    }
}
