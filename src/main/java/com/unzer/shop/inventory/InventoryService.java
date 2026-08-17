package com.unzer.shop.inventory;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InventoryService {

    private final JdbcTemplate jdbc;
    private final ReservationRepository reservationRepository;

    @Transactional
    public boolean reserve(UUID orderId, UUID variantId, int qty, Duration ttl) {
        int updated = jdbc.update(
                """
                UPDATE inventory.stock
                   SET reserved = reserved + ?
                 WHERE variant_id = ?
                   AND on_hand - reserved >= ?
                """,
                qty, variantId, qty);

        if (updated == 0) {
            log.info("Reservation rejected: insufficient stock for variant={} qty={}", variantId, qty);
            return false;
        }

        Reservation reservation = new Reservation();
        reservation.setOrderId(orderId);
        reservation.setVariantId(variantId);
        reservation.setQty(qty);
        reservation.setExpiresAt(Instant.now().plus(ttl));
        reservation.setStatus(ReservationStatus.ACTIVE);
        reservationRepository.save(reservation);

        return true;
    }

    @Transactional
    public void releaseByOrder(UUID orderId) {
        List<Reservation> active = reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE);
        for (Reservation r : active) {
            int updated = jdbc.update(
                    "UPDATE inventory.stock SET reserved = reserved - ? WHERE variant_id = ?",
                    r.getQty(), r.getVariantId());
            if (updated > 0) {
                r.setStatus(ReservationStatus.RELEASED);
                reservationRepository.save(r);
            }
        }
    }

    @Transactional
    public boolean commitByOrder(UUID orderId) {
        List<Reservation> active = reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE);
        if (active.isEmpty()) {
            log.warn("commitByOrder found no ACTIVE reservations for order={} (likely expired)", orderId);
            return false;
        }

        for (Reservation r : active) {
            int updated = jdbc.update(
                    """
                    UPDATE inventory.stock
                       SET on_hand = on_hand - ?, reserved = reserved - ?
                     WHERE variant_id = ?
                    """,
                    r.getQty(), r.getQty(), r.getVariantId());
            if (updated == 0) {
                log.error("Failed to commit reservation {} for order={}", r.getId(), orderId);
                return false;
            }
            r.setStatus(ReservationStatus.COMMITTED);
            reservationRepository.save(r);
        }
        return true;
    }

    @Transactional
    public void restockByOrder(UUID orderId) {
        List<Reservation> committed = reservationRepository.findByOrderIdAndStatus(orderId, ReservationStatus.COMMITTED);
        for (Reservation r : committed) {
            int updated = jdbc.update(
                    "UPDATE inventory.stock SET on_hand = on_hand + ? WHERE variant_id = ?",
                    r.getQty(), r.getVariantId());
            if (updated > 0) {
                r.setStatus(ReservationStatus.RELEASED);
                reservationRepository.save(r);
            }
        }
    }

    @Transactional
    public java.util.Set<UUID> releaseExpiredReservations() {
        List<Reservation> expired = reservationRepository.findByStatusAndExpiresAtBefore(
                ReservationStatus.ACTIVE, Instant.now());

        java.util.Set<UUID> affectedOrders = new java.util.HashSet<>();
        for (Reservation r : expired) {
            int updated = jdbc.update(
                    "UPDATE inventory.stock SET reserved = reserved - ? WHERE variant_id = ?",
                    r.getQty(), r.getVariantId());
            if (updated > 0) {
                r.setStatus(ReservationStatus.RELEASED);
                reservationRepository.save(r);
                affectedOrders.add(r.getOrderId());
            }
        }
        if (!affectedOrders.isEmpty()) {
            log.info("Released {} expired reservation(s) across {} order(s)", expired.size(), affectedOrders.size());
        }
        return affectedOrders;
    }
}
