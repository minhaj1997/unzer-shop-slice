package com.unzer.shop.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@RestController
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final PaymentService paymentService;
    private final ProcessedWebhookRepository processedWebhookRepository;
    private final ObjectMapper objectMapper;

    // Unzer sends text/plain (not application/json) for webhook bodies.
    @PostMapping(value = "/webhooks/unzer", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> handle(@RequestBody String rawBody) {
        String fingerprint = sha256(rawBody);

        if (!tryMarkProcessed(fingerprint)) {
            log.debug("Duplicate webhook delivery (fingerprint={}), skipping re-processing", fingerprint);
            return ResponseEntity.ok().build();
        }

        try {
            JsonNode json = objectMapper.readTree(rawBody);
            String paymentId = json.path("paymentId").asText(null);
            if (paymentId == null || paymentId.isBlank()) {
                log.warn("Webhook payload had no paymentId, ignoring: {}", rawBody);
                return ResponseEntity.ok().build();
            }

            paymentService.reconcile(paymentId);
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Webhook processing failed for fingerprint={}", fingerprint, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    private boolean tryMarkProcessed(String fingerprint) {
        try {
            processedWebhookRepository.save(new ProcessedWebhook(fingerprint));
            return true;
        } catch (DataIntegrityViolationException e) {
            return false;
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
