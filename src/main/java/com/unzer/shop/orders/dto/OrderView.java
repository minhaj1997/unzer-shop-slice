package com.unzer.shop.orders.dto;

import java.util.List;
import java.util.UUID;

public record OrderView(
        UUID id,
        String status,
        long totalMinor,
        String currency,
        List<Item> items,
        List<HistoryEntry> history
) {
    public record Item(String sku, String name, long unitPriceMinor, int qty) {
    }

    public record HistoryEntry(String from, String to, String cause, String occurredAt) {
    }
}
