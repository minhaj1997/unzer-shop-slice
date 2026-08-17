package com.unzer.shop.orders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.unzer.shop.inventory.InventoryService;
import com.unzer.shop.orders.dto.CheckoutRequest;
import com.unzer.shop.orders.dto.CheckoutResult;
import com.unzer.shop.payments.PaymentGateway;
import com.unzer.shop.payments.PaymentInitiation;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CheckoutService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final CheckoutTransaction checkoutTransaction;
    private final OrderService orderService;
    private final PaymentGateway paymentGateway;
    private final InventoryService inventoryService;
    private final ObjectMapper objectMapper;

    public CheckoutResult checkout(String idempotencyKey, CheckoutRequest request) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByKey(idempotencyKey);
            if (existing.isPresent()) {
                log.info("Idempotency-Key {} already used — returning original response", idempotencyKey);
                return deserialize(existing.get().getResponseBody());
            }
        }

        CheckoutTransaction.Result reservation = checkoutTransaction.run(request);

        if (!reservation.success()) {
            CheckoutResult result = CheckoutResult.outOfStock(
                    reservation.orderId(), OrderStatus.CANCELLED.name(), reservation.failedSku());
            saveIdempotencyKey(idempotencyKey, reservation.orderId(), result);
            return result;
        }

        PaymentInitiation initiation;
        try {
            initiation = paymentGateway.initiate(
                    reservation.orderId(), request.method(), reservation.totalMinor(), "EUR", request.clientTypeId());
        } catch (RuntimeException e) {
            log.warn("Payment initiation failed for order {}, releasing reservation", reservation.orderId(), e);
            inventoryService.releaseByOrder(reservation.orderId());
            orderService.transition(reservation.orderId(), OrderStatus.PAYMENT_FAILED, "payment_initiation_failed");
            CheckoutResult result = CheckoutResult.paymentFailed(
                    reservation.orderId(), OrderStatus.PAYMENT_FAILED.name(), e.getMessage());
            saveIdempotencyKey(idempotencyKey, reservation.orderId(), result);
            return result;
        }

        orderService.transition(reservation.orderId(), OrderStatus.AWAITING_PAYMENT, "payment_initiated");

        CheckoutResult result = CheckoutResult.ok(
                reservation.orderId(), OrderStatus.AWAITING_PAYMENT.name(), initiation.redirectUrl());
        saveIdempotencyKey(idempotencyKey, reservation.orderId(), result);
        return result;
    }

    private void saveIdempotencyKey(String key, UUID orderId, CheckoutResult result) {
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
    private String serialize(CheckoutResult result) {
        return objectMapper.writeValueAsString(result);
    }

    @SneakyThrows
    private CheckoutResult deserialize(String json) {
        return objectMapper.readValue(json, CheckoutResult.class);
    }
}
