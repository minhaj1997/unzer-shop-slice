package com.unzer.shop.orders;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final OrderStateMachine stateMachine;

    public OrderStatus getStatus(UUID orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No such order: " + orderId))
                .getStatus();
    }

    @Transactional
    public OrderStateMachine.Result transition(UUID orderId, OrderStatus to, String cause) {
        Order order = orderRepository.lockById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No such order: " + orderId));

        OrderStatus from = order.getStatus();
        OrderStateMachine.Result result = stateMachine.check(from, to);

        switch (result) {
            case APPLIED -> {
                order.setStatus(to);
                orderRepository.save(order);
                appendHistory(orderId, from, to, cause);
                log.info("Order {} transitioned {} -> {} (cause={})", orderId, from, to, cause);
            }
            case NOOP_ALREADY_THERE ->
                    log.debug("Order {} already {} — ignoring redundant transition (cause={})", orderId, to, cause);
            case REJECTED_ILLEGAL ->
                    log.warn("Rejected illegal transition for order {}: {} -> {} (cause={})", orderId, from, to, cause);
        }

        return result;
    }

    @Transactional
    public void appendHistory(UUID orderId, OrderStatus from, OrderStatus to, String cause) {
        OrderStatusHistory history = new OrderStatusHistory();
        history.setOrderId(orderId);
        history.setFromStatus(from == null ? null : from.name());
        history.setToStatus(to.name());
        history.setCause(cause);
        historyRepository.save(history);
    }
}
