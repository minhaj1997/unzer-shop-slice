package com.unzer.shop.orders;

import com.unzer.shop.inventory.InventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class CheckoutAbandonmentSweepJob {

    private final InventoryService inventoryService;
    private final OrderService orderService;

    @Scheduled(fixedDelay = 30_000)
    public void sweep() {
        Set<UUID> affectedOrders = inventoryService.releaseExpiredReservations();
        for (UUID orderId : affectedOrders) {
            orderService.transition(orderId, OrderStatus.CANCELLED, "reservation_ttl_expired");
        }
    }
}
