package com.unzer.shop.inventory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class InventoryConcurrencyTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("shop_test")
            .withUsername("shop_test")
            .withPassword("shop_test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("unzer.private-key", () -> "s-priv-test-0000000000000000");
        registry.add("unzer.return-url", () -> "http://localhost:8080/api/orders/return");
    }

    @Autowired
    private InventoryService inventoryService;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID variantId;

    @BeforeEach
    void seedScarceStock() {
        UUID productId = UUID.randomUUID();
        jdbc.update("INSERT INTO catalog.product (id, name) VALUES (?, ?)", productId, "Concurrency Test Product");

        variantId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO catalog.variant (id, product_id, sku, attributes, price_minor, currency)
                VALUES (?, ?, ?, '{}'::jsonb, ?, ?)
                """, variantId, productId, "CONCURRENCY-TEST-" + variantId, 999L, "EUR");

        jdbc.update("INSERT INTO inventory.stock (variant_id, on_hand, reserved) VALUES (?, 1, 0)", variantId);
    }

    @Test
    void exactlyOneOfManyConcurrentBuyersWinsTheLastUnit() throws InterruptedException {
        int concurrentBuyers = 25;
        ExecutorService pool = Executors.newFixedThreadPool(concurrentBuyers);
        CountDownLatch allThreadsReady = new CountDownLatch(concurrentBuyers);
        CountDownLatch startSignal = new CountDownLatch(1);

        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < concurrentBuyers; i++) {
            UUID orderId = UUID.randomUUID();
            tasks.add(() -> {
                allThreadsReady.countDown();
                startSignal.await();
                return inventoryService.reserve(orderId, variantId, 1, Duration.ofMinutes(30));
            });
        }

        List<Future<Boolean>> futures = new ArrayList<>();
        for (Callable<Boolean> task : tasks) {
            futures.add(pool.submit(task));
        }

        allThreadsReady.await(5, TimeUnit.SECONDS);
        startSignal.countDown();

        long successCount = futures.stream()
                .map(this::getResult)
                .filter(Boolean::booleanValue)
                .count();

        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        assertThat(successCount).isEqualTo(1);

        Integer reserved = jdbc.queryForObject(
                "SELECT reserved FROM inventory.stock WHERE variant_id = ?", Integer.class, variantId);
        Integer onHand = jdbc.queryForObject(
                "SELECT on_hand FROM inventory.stock WHERE variant_id = ?", Integer.class, variantId);

        assertThat(reserved).isEqualTo(1);
        assertThat(reserved).isLessThanOrEqualTo(onHand);

        Integer activeReservationCount = jdbc.queryForObject(
                "SELECT count(*) FROM inventory.reservation WHERE variant_id = ? AND status = 'ACTIVE'",
                Integer.class, variantId);
        assertThat(activeReservationCount).isEqualTo(1);
    }

    @Test
    void reservingMoreThanAvailableFailsCleanly() {
        boolean result = inventoryService.reserve(UUID.randomUUID(), variantId, 2, Duration.ofMinutes(30));

        assertThat(result).isFalse();

        Integer reserved = jdbc.queryForObject(
                "SELECT reserved FROM inventory.stock WHERE variant_id = ?", Integer.class, variantId);
        assertThat(reserved).isEqualTo(0);
    }

    private boolean getResult(Future<Boolean> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
