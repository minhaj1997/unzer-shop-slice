package com.unzer.shop.payments;

import com.unzer.payment.Unzer;
import com.unzer.shop.config.UnzerProperties;
import com.unzer.shop.orders.OrderService;
import com.unzer.shop.orders.OrderStateMachine;
import com.unzer.shop.orders.OrderStatus;
import com.unzer.shop.inventory.InventoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PaymentService implements PaymentGateway {

    private final Map<PaymentMethod, PaymentMethodHandler> handlers;
    private final PaymentRepository paymentRepository;
    private final PaymentTransactionRepository transactionRepository;
    private final RefundRepository refundRepository;
    private final OrderService orderService;
    private final InventoryService inventoryService;
    private final Unzer unzer;
    private final UnzerProperties unzerProperties;

    public PaymentService(List<PaymentMethodHandler> handlerList,
                           PaymentRepository paymentRepository,
                           PaymentTransactionRepository transactionRepository,
                           RefundRepository refundRepository,
                           OrderService orderService,
                           InventoryService inventoryService,
                           Unzer unzer,
                           UnzerProperties unzerProperties) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(PaymentMethodHandler::method, Function.identity()));
        this.paymentRepository = paymentRepository;
        this.transactionRepository = transactionRepository;
        this.refundRepository = refundRepository;
        this.orderService = orderService;
        this.inventoryService = inventoryService;
        this.unzer = unzer;
        this.unzerProperties = unzerProperties;
    }

    @Override
    @Transactional
    public PaymentInitiation initiate(UUID orderId, PaymentMethod method, long amountMinor,
                                       String currency, String clientTypeId) {
        PaymentMethodHandler handler = handlers.get(method);
        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for payment method " + method);
        }

        InitiateContext ctx = new InitiateContext(
                orderId, amountMinor, currency, unzerProperties.getReturnUrl(), clientTypeId);

        PaymentInitiation result = handler.initiate(ctx);

        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setMethod(method);
        payment.setStatus(result.status());
        payment.setUnzerPaymentId(result.unzerPaymentId());
        payment.setUnzerTypeId(result.unzerTypeId());
        payment.setAmountMinor(amountMinor);
        payment.setCurrency(currency);
        paymentRepository.save(payment);

        PaymentTransaction tx = new PaymentTransaction();
        tx.setPaymentId(payment.getId());
        tx.setKind(method == PaymentMethod.CARD ? TransactionKind.AUTHORIZE : TransactionKind.CHARGE);
        tx.setUnzerTxId(result.unzerTransactionId());
        tx.setStatus(result.rawUnzerStatus());
        transactionRepository.save(tx);

        log.info("Initiated {} payment for order={}: unzerPaymentId={} status={}",
                method, orderId, result.unzerPaymentId(), result.status());

        return result;
    }

    @Override
    @Transactional
    public void capture(UUID orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("No payment found for order " + orderId));
        PaymentMethodHandler handler = handlers.get(payment.getMethod());
        if (handler == null) {
            throw new IllegalStateException("No handler registered for payment method " + payment.getMethod());
        }
        handler.capture(payment);
    }

    @Override
    @Transactional
    public RefundOutcome refund(UUID orderId, long amountMinor, String reason) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("No payment found for order " + orderId));

        long totalRefunded = issueRefund(payment, amountMinor, reason);
        boolean fullyRefunded = totalRefunded == payment.getAmountMinor();

        OrderStatus resultingStatus = orderService.getStatus(orderId);
        if (fullyRefunded) {
            OrderStatus target = resultingStatus == OrderStatus.PAID ? OrderStatus.CANCELLED : OrderStatus.REFUNDED;
            String cause = target == OrderStatus.CANCELLED ? "customer_cancelled_pre_fulfillment" : "refund:full";
            OrderStateMachine.Result result = orderService.transition(orderId, target, cause);
            if (result == OrderStateMachine.Result.APPLIED) {
                resultingStatus = target;
                if (target == OrderStatus.CANCELLED) {
                    inventoryService.restockByOrder(orderId);
                }
            }
        }

        log.info("Refunded {} minor units for order={} (total refunded={}/{}, fullyRefunded={}, reason='{}')",
                amountMinor, orderId, totalRefunded, payment.getAmountMinor(), fullyRefunded, reason);

        return new RefundOutcome(resultingStatus, fullyRefunded, totalRefunded);
    }

    private long issueRefund(Payment payment, long amountMinor, String reason) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }
        PaymentMethodHandler handler = handlers.get(payment.getMethod());
        if (handler == null) {
            throw new IllegalStateException("No handler registered for payment method " + payment.getMethod());
        }

        long alreadyRefunded = refundRepository.findByPaymentId(payment.getId()).stream()
                .mapToLong(Refund::getAmountMinor)
                .sum();
        long newTotal = alreadyRefunded + amountMinor;
        if (newTotal > payment.getAmountMinor()) {
            throw new IllegalArgumentException("Refund of " + amountMinor + " would bring total refunded to " +
                    newTotal + ", exceeding the original payment amount " + payment.getAmountMinor());
        }
        if (payment.getStatus() == PaymentStatus.AUTHORIZED && amountMinor != payment.getAmountMinor()) {
            throw new IllegalArgumentException("Payment " + payment.getId() +
                    " is only authorized, not captured — only a full void is possible, not a partial refund");
        }

        String unzerTxId = handler.refund(payment, amountMinor, reason);
        refundRepository.save(new Refund(payment.getId(), amountMinor, reason, unzerTxId));
        return newTotal;
    }

    @Override
    @Transactional
    public ReconcileOutcome reconcile(String unzerPaymentId) {
        Payment payment = paymentRepository.lockByUnzerPaymentId(unzerPaymentId)
                .orElseThrow(() -> new IllegalStateException("Unknown Unzer paymentId: " + unzerPaymentId));

        com.unzer.payment.Payment remote = unzer.fetchPayment(unzerPaymentId);
        com.unzer.payment.BasePayment.State remoteState = remote.getPaymentState();
        String rawState = remoteState == null ? null : remoteState.name();
        PaymentStatus target = mapState(rawState, payment.getStatus());

        if (target == payment.getStatus()) {
            log.debug("reconcile: payment {} already {} — no-op", unzerPaymentId, target);
            return new ReconcileOutcome(payment.getOrderId(), target, false);
        }

        payment.setStatus(target);
        paymentRepository.save(payment);

        PaymentTransaction tx = new PaymentTransaction();
        tx.setPaymentId(payment.getId());
        tx.setKind(payment.getMethod() == PaymentMethod.CARD ? TransactionKind.AUTHORIZE : TransactionKind.CHARGE);
        tx.setUnzerTxId(unzerPaymentId);
        tx.setStatus(rawState);
        transactionRepository.save(tx);

        log.info("reconcile: payment {} for order={} -> {} (raw='{}')",
                unzerPaymentId, payment.getOrderId(), target, rawState);

        applyOrderSideEffects(payment.getOrderId(), target);

        return new ReconcileOutcome(payment.getOrderId(), target, true);
    }

    private void applyOrderSideEffects(UUID orderId, PaymentStatus status) {
        switch (status) {
            case PAID -> onPaid(orderId);
            case FAILED -> onFailed(orderId);
            case AUTHORIZED, PENDING -> { }
        }
    }

    private void onPaid(UUID orderId) {
        OrderStateMachine.Result result = orderService.transition(orderId, OrderStatus.PAID, "payment:PAID");
        if (result != OrderStateMachine.Result.APPLIED) {
            return;
        }

        boolean committed = inventoryService.commitByOrder(orderId);
        if (!committed) {
            log.warn("Stock unavailable for order {} after payment succeeded — refunding in full", orderId);
            Payment payment = paymentRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new IllegalStateException("No payment found for order " + orderId));
            issueRefund(payment, payment.getAmountMinor(), "stock_lost_after_payment");
            orderService.transition(orderId, OrderStatus.REFUNDED, "stock_lost_after_payment");
        }
    }

    private void onFailed(UUID orderId) {
        OrderStateMachine.Result result = orderService.transition(orderId, OrderStatus.PAYMENT_FAILED, "payment:FAILED");
        if (result == OrderStateMachine.Result.APPLIED) {
            inventoryService.releaseByOrder(orderId);
        }
    }

    // com.unzer.payment.BasePayment.State's real values (confirmed against the SDK sources jar):
    // PENDING, COMPLETED, CANCELED, PARTLY, PAYMENT_REVIEW, CHARGEBACK, CREATE.
    private PaymentStatus mapState(String rawState, PaymentStatus current) {
        if (rawState == null) {
            return current;
        }
        return switch (rawState.toLowerCase()) {
            case "completed" -> PaymentStatus.PAID;
            case "canceled", "chargeback" -> PaymentStatus.FAILED;
            case "pending", "create", "payment_review", "partly" ->
                    current == PaymentStatus.AUTHORIZED ? PaymentStatus.AUTHORIZED : PaymentStatus.PENDING;
            default -> {
                log.warn("Unrecognized Unzer payment state '{}', leaving status unchanged", rawState);
                yield current;
            }
        };
    }
}
