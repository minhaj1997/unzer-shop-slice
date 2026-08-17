package com.unzer.shop.orders;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.unzer.shop.orders.OrderStateMachine.Result.*;
import static com.unzer.shop.orders.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class OrderStateMachineTest {

    private final OrderStateMachine machine = new OrderStateMachine();

    @ParameterizedTest(name = "{0} -> {1} should be {2}")
    @CsvSource({
            "CREATED, AWAITING_PAYMENT, APPLIED",
            "CREATED, CANCELLED, APPLIED",
            "CREATED, PAID, REJECTED_ILLEGAL",
            "AWAITING_PAYMENT, PAID, APPLIED",
            "AWAITING_PAYMENT, PAYMENT_FAILED, APPLIED",
            "AWAITING_PAYMENT, CANCELLED, APPLIED",
            "AWAITING_PAYMENT, AWAITING_PAYMENT, NOOP_ALREADY_THERE",
            "PAYMENT_FAILED, AWAITING_PAYMENT, APPLIED",
            "PAYMENT_FAILED, PAID, REJECTED_ILLEGAL",
            "PAID, FULFILLING, APPLIED",
            "PAID, REFUNDED, APPLIED",
            "PAID, CANCELLED, APPLIED",
            "PAID, PAID, NOOP_ALREADY_THERE",
            "PAID, AWAITING_PAYMENT, REJECTED_ILLEGAL",
            "FULFILLING, SHIPPED, APPLIED",
            "FULFILLING, REFUNDED, APPLIED",
            "SHIPPED, COMPLETED, APPLIED",
            "SHIPPED, REFUNDED, APPLIED",
            "COMPLETED, REFUNDED, APPLIED",
            "COMPLETED, CANCELLED, REJECTED_ILLEGAL",
            "CANCELLED, AWAITING_PAYMENT, REJECTED_ILLEGAL",
            "REFUNDED, PAID, REJECTED_ILLEGAL"
    })
    void transitions(OrderStatus from, OrderStatus to, OrderStateMachine.Result expected) {
        assertThat(machine.check(from, to)).isEqualTo(expected);
    }

    @Test
    void everyTerminalStateHasNoOutgoingTransitions() {
        for (OrderStatus terminal : new OrderStatus[]{CANCELLED, REFUNDED}) {
            for (OrderStatus candidate : OrderStatus.values()) {
                if (candidate == terminal) {
                    continue;
                }
                assertThat(machine.check(terminal, candidate))
                        .as("%s -> %s should be rejected (terminal state)", terminal, candidate)
                        .isEqualTo(REJECTED_ILLEGAL);
            }
        }
    }

    @Test
    void completedOnlyAllowsReturn() {
        for (OrderStatus candidate : OrderStatus.values()) {
            if (candidate == COMPLETED || candidate == REFUNDED) {
                continue;
            }
            assertThat(machine.check(COMPLETED, candidate))
                    .as("COMPLETED -> %s should be rejected", candidate)
                    .isEqualTo(REJECTED_ILLEGAL);
        }
        assertThat(machine.check(COMPLETED, REFUNDED)).isEqualTo(APPLIED);
    }

    @Test
    void applyingTheSameTransitionTwiceIsIdempotent() {
        assertThat(machine.check(AWAITING_PAYMENT, PAID)).isEqualTo(APPLIED);
        assertThat(machine.check(PAID, PAID)).isEqualTo(NOOP_ALREADY_THERE);
    }
}
