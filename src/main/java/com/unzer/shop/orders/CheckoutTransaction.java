package com.unzer.shop.orders;

import com.unzer.shop.catalog.Variant;
import com.unzer.shop.catalog.VariantRepository;
import com.unzer.shop.inventory.InventoryService;
import com.unzer.shop.orders.dto.CheckoutRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// Isolated into its own bean so @Transactional gets a real proxy boundary — calling it from
// CheckoutService as a self-invocation would silently skip the transaction.
@Service
@RequiredArgsConstructor
public class CheckoutTransaction {

    private static final Duration RESERVATION_TTL = Duration.ofMinutes(30);

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderService orderService;
    private final VariantRepository variantRepository;
    private final InventoryService inventoryService;

    @Transactional
    public Result run(CheckoutRequest request) {
        Order order = new Order();
        order.setStatus(OrderStatus.CREATED);
        order.setGuestEmail(request.guestEmail());
        order.setCurrency("EUR");
        order.setTotalMinor(0);
        orderRepository.save(order);

        List<OrderItem> items = new ArrayList<>();
        long total = 0;
        for (CheckoutRequest.Line line : request.items()) {
            Variant variant = variantRepository.findById(line.variantId())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown variant " + line.variantId()));

            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setVariantId(variant.getId());
            item.setSku(variant.getSku());
            item.setName(variant.getSku());
            item.setUnitPriceMinor(variant.getPriceMinor());
            item.setQty(line.qty());
            orderItemRepository.save(item);
            items.add(item);

            total += variant.getPriceMinor() * line.qty();
        }

        order.setTotalMinor(total);
        orderRepository.save(order);
        orderService.appendHistory(order.getId(), null, OrderStatus.CREATED, "checkout_submitted");

        for (OrderItem item : items) {
            boolean reserved = inventoryService.reserve(order.getId(), item.getVariantId(), item.getQty(), RESERVATION_TTL);
            if (!reserved) {
                inventoryService.releaseByOrder(order.getId());
                orderService.transition(order.getId(), OrderStatus.CANCELLED, "out_of_stock:" + item.getSku());
                return Result.outOfStock(order.getId(), item.getSku());
            }
        }

        orderService.transition(order.getId(), OrderStatus.AWAITING_PAYMENT, "stock_reserved");
        return Result.success(order.getId(), total);
    }

    public record Result(boolean success, UUID orderId, long totalMinor, String failedSku) {
        static Result success(UUID orderId, long totalMinor) {
            return new Result(true, orderId, totalMinor, null);
        }

        static Result outOfStock(UUID orderId, String sku) {
            return new Result(false, orderId, 0, sku);
        }
    }
}
