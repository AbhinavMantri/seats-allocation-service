package com.example.seats_allocation_service.dtos;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ReleaseSeatsRequest {
    @NotNull(message = "eventId is required")
    private String eventId;

    @NotNull(message = "bookingId is required")
    private String bookingId;

    @NotEmpty(message = "seatIds is required")
    private List<@NotNull(message = "Each seatId is required") String> seatIds;

    @NotNull(message = "reason is required")
    private ReleaseReason reason;
}
