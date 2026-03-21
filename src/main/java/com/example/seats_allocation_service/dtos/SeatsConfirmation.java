package com.example.seats_allocation_service.dtos;

import java.util.List;
import java.util.UUID;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SeatsConfirmation {
    private UUID eventId;
    private UUID bookingId;
    private UUID paymentId;
    private List<UUID> seatIds;
    private Integer bookedCount;
    private String confirmedAt;
}
