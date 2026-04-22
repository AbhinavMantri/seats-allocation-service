package com.example.seats_allocation_service.dtos;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class ReleaseLocksRequest {
    @NotNull(message = "userId is required")
    private UUID userId;

    @NotEmpty(message = "seatIds is required")
    private List<UUID> seatIds;
}
