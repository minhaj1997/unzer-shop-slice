package com.unzer.shop.orders;

import com.unzer.shop.orders.dto.CheckoutRequest;
import com.unzer.shop.orders.dto.CheckoutResult;
import com.unzer.shop.orders.dto.OrderView;
import com.unzer.shop.orders.dto.RefundRequest;
import com.unzer.shop.orders.dto.RefundResult;
import com.unzer.shop.payments.PaymentGateway;
import com.unzer.shop.payments.PaymentRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class OrderController {

    private final CheckoutService checkoutService;
    private final RefundService refundService;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final OrderStatusHistoryRepository historyRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;

    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResult> checkout(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CheckoutRequest request) {

        CheckoutResult result = checkoutService.checkout(idempotencyKey, request);
        HttpStatus status = result.success() ? HttpStatus.OK : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/orders/{id}")
    public ResponseEntity<OrderView> getOrder(@PathVariable UUID id) {
        return orderRepository.findById(id)
                .map(this::toView)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/orders/{id}/refund")
    public ResponseEntity<RefundResult> refund(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @PathVariable UUID id,
            @Valid @RequestBody RefundRequest request) {

        if (orderRepository.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        RefundResult result = refundService.refund(idempotencyKey, id, request);
        HttpStatus status = result.success() ? HttpStatus.OK : HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(result);
    }

    @GetMapping("/orders/{id}/return")
    public ResponseEntity<OrderView> handleReturn(@PathVariable UUID id) {
        orderRepository.findById(id).ifPresent(order ->
                paymentRepository.findByOrderId(id).ifPresent(payment -> {
                    if (payment.getUnzerPaymentId() != null) {
                        paymentGateway.reconcile(payment.getUnzerPaymentId());
                    }
                }));
        return getOrder(id);
    }

    private OrderView toView(Order order) {
        List<OrderView.Item> items = orderItemRepository.findByOrderId(order.getId()).stream()
                .map(i -> new OrderView.Item(i.getSku(), i.getName(), i.getUnitPriceMinor(), i.getQty()))
                .toList();

        List<OrderView.HistoryEntry> history = historyRepository.findByOrderIdOrderByOccurredAtAsc(order.getId())
                .stream()
                .map(h -> new OrderView.HistoryEntry(
                        h.getFromStatus(), h.getToStatus(), h.getCause(),
                        DateTimeFormatter.ISO_INSTANT.format(h.getOccurredAt())))
                .toList();

        return new OrderView(order.getId(), order.getStatus().name(), order.getTotalMinor(),
                order.getCurrency(), items, history);
    }
}
