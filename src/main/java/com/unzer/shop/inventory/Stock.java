package com.unzer.shop.inventory;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "stock", schema = "inventory")
@Getter
@NoArgsConstructor
public class Stock {

    @Id
    @Column(name = "variant_id")
    private UUID variantId;

    private int onHand;

    private int reserved;

    public int available() {
        return onHand - reserved;
    }
}
