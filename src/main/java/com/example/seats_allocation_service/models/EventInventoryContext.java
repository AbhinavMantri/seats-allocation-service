package com.example.seats_allocation_service.models;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.util.UUID;

@Entity
@Table(name = "event_inventory_context")
@AttributeOverride(name = "id", column = @Column(name = "event_id", nullable = false, updatable = false))
@Data
public class EventInventoryContext extends BaseEntity {

    @Column(name = "venue_id", nullable = false)
    private UUID venueId;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InventoryStatus status = InventoryStatus.ACTIVE;

    @PrePersist
    private void prePersist() {
        if (status == null) {
            status = InventoryStatus.ACTIVE;
        }
    }

    public enum InventoryStatus {
        ACTIVE,
        CANCELLED
    }
}
