package com.unzer.shop.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unzer.shop.orders.dto.RefundRequest;
import com.unzer.shop.orders.dto.RefundResult;
import com.unzer.shop.payments.PaymentGateway;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefundService {

    private static final Set<OrderStatus> REFUNDABLE =
            EnumSet.of(OrderStatus.PAID, OrderStatus.FULFILLING, OrderStatus.SHIPPED, OrderStatus.COMPLETED);

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OrderRepository orderRepository;
    private final PaymentGateway paymentGateway;
    private final ObjectMapper objectMapper;

    public RefundResult refund(String idempotencyKey, UUID orderId, RefundRequest request) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotency-Key {} already used — returning original response", idempotencyKey);
                return deserialize(existing.get().getResponseBody());
            }
        }

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("No such order: " + orderId));

        RefundResult result;
        if (!REFUNDABLE.contains(order.getStatus())) {
            result = RefundResult.rejected(orderId, order.getStatus().name(),
                    "Order is not in a refundable state: " + order.getStatus());
        } else {
            try {
                PaymentGateway.RefundOutcome outcome =
                        paymentGateway.refund(orderId, request.amountMinor(), request.reason());
                result = RefundResult.ok(orderId, outcome.orderStatus().name(),
                        request.amountMinor(), outcome.totalRefundedMinor(), outcome.fullyRefunded());
            } catch (RuntimeException e) {
                log.warn("Refund failed for order {}", orderId, e);
                result = RefundResult.rejected(orderId, order.getStatus().name(), "refund_failed: " + e.getMessage());
            }
        }

        saveIdempotencyKey(idempotencyKey, orderId, result);
        return result;
    }

    private void saveIdempotencyKey(String key, UUID orderId, RefundResult result) {
        if (key == null || key.isBlank()) {
            return;
        }
        try {
            idempotencyKeyRepository.save(new IdempotencyKey(key, orderId, serialize(result)));
        } catch (DataIntegrityViolationException e) {
            log.warn("Idempotency-Key {} race lost on save — see known-limitations note in README", key);
        }
    }

    @SneakyThrows
    private String serialize(RefundResult result) {
        return objectMapper.writeValueAsString(result);
    }

    @SneakyThrows
    private RefundResult deserialize(String json) {
        return objectMapper.readValue(json, RefundResult.class);
    }
}
