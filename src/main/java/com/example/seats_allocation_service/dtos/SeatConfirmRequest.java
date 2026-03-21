package com.example.seats_allocation_service.dtos;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class SeatConfirmRequest {
    @NotNull(message = "eventId is required")
    private UUID eventId;

    @NotNull(message = "bookingId is required")
    private UUID bookingId;

    @NotNull(message = "paymentId is required")
    private UUID paymentId;

    @NotEmpty(message = "seatIds is required")
    private List<@NotNull(message = "Each seatId is required") UUID> seatIds;

    @NotNull(message = "confirmedAt is required")
    private Instant confirmedAt;
}
