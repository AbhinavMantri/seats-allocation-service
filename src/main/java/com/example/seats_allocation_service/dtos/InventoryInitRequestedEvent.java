package com.example.seats_allocation_service.dtos;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
public class InventoryInitRequestedEvent {
    private String requestId;
    private UUID eventId;
    private UUID venueId;
    private String inventoryType;
    private Integer seatMapVersion;
    private Instant triggeredAt;
}
