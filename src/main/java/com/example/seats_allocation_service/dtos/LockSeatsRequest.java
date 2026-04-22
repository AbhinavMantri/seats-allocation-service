package com.example.seats_allocation_service.dtos;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class LockSeatsRequest {
    private UUID bookingId;

    @NotNull(message = "userId is required")
    private UUID userId;

    @NotBlank(message = "idempotencyKey is required")
    private String idempotencyKey;
 
    @NotEmpty(message = "seatIds is required")
    @NotNull(message = "Each seatId is required")
    private List<UUID> seatIds;
}
