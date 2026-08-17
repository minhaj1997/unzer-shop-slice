package com.unzer.shop.payments;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "refund", schema = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    private String reason;

    @Column(name = "unzer_tx_id")
    private String unzerTxId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public Refund(UUID paymentId, long amountMinor, String reason, String unzerTxId) {
        this.paymentId = paymentId;
        this.amountMinor = amountMinor;
        this.reason = reason;
        this.unzerTxId = unzerTxId;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
