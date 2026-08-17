package com.unzer.shop.orders.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record RefundRequest(
        @Positive long amountMinor,
        @NotBlank String reason
) {
}
