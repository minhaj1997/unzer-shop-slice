package com.unzer.shop.orders;

import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.unzer.shop.orders.OrderStatus.*;

@Component
public class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            CREATED, EnumSet.of(AWAITING_PAYMENT, CANCELLED),
            AWAITING_PAYMENT, EnumSet.of(PAID, PAYMENT_FAILED, CANCELLED),
            PAYMENT_FAILED, EnumSet.of(AWAITING_PAYMENT),
            PAID, EnumSet.of(FULFILLING, REFUNDED, CANCELLED),
            FULFILLING, EnumSet.of(SHIPPED, REFUNDED),
            SHIPPED, EnumSet.of(COMPLETED, REFUNDED),
            COMPLETED, EnumSet.of(REFUNDED),
            CANCELLED, EnumSet.noneOf(OrderStatus.class),
            REFUNDED, EnumSet.noneOf(OrderStatus.class)
    );

    public enum Result {
        APPLIED,
        NOOP_ALREADY_THERE,
        REJECTED_ILLEGAL
    }

    public Result check(OrderStatus from, OrderStatus to) {
        if (from == to) {
            return Result.NOOP_ALREADY_THERE;
        }
        Set<OrderStatus> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        return allowed.contains(to) ? Result.APPLIED : Result.REJECTED_ILLEGAL;
    }
}
