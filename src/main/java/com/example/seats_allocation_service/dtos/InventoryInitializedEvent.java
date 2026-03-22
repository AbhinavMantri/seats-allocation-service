package com.example.seats_allocation_service.dtos;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryInitializedEvent {
    private String requestId;
    private UUID eventId;
    private String status;
    private Integer totalSeats;
    private Integer seatMapVersion;
    private Instant processedAt;
    private String errorCode;
    private String message;
}
