package com.unzer.shop.payments;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Implements {@link Persistable} because {@code fingerprint} is a manually-assigned {@code @Id};
 * without it, Spring Data would call {@code merge()} instead of {@code persist()} and a duplicate
 * delivery would silently overwrite the row instead of hitting the primary-key constraint.
 */
@Entity
@Table(name = "processed_webhook", schema = "shared")
@Getter
@Setter
@NoArgsConstructor
public class ProcessedWebhook implements Persistable<String> {

    @Id
    private String fingerprint;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean isNew = true;

    public ProcessedWebhook(String fingerprint) {
        this.fingerprint = fingerprint;
        this.processedAt = Instant.now();
    }

    @Override
    public String getId() {
        return fingerprint;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }
}
