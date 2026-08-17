package com.unzer.shop.payments;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class ReconciliationPollerJob {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway paymentGateway;

    @Scheduled(fixedDelay = 60_000)
    public void poll() {
        List<Payment> pending = paymentRepository.findByStatus(PaymentStatus.PENDING);
        for (Payment payment : pending) {
            if (payment.getUnzerPaymentId() == null) {
                continue;
            }
            try {
                paymentGateway.reconcile(payment.getUnzerPaymentId());
            } catch (Exception e) {
                log.warn("Reconciliation poll failed for payment {} (unzerPaymentId={})",
                        payment.getId(), payment.getUnzerPaymentId(), e);
            }
        }
    }
}
