package com.unzer.shop.payments;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedWebhookRepository extends JpaRepository<ProcessedWebhook, String> {
}
