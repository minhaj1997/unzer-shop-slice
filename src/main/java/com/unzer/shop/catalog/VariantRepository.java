package com.unzer.shop.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VariantRepository extends JpaRepository<Variant, UUID> {
}
