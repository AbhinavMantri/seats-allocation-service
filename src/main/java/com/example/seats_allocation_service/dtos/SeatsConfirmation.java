package com.example.seats_allocation_service.dtos;

import java.util.List;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeatsConfirmation {
    private UUID eventId;
    private UUID bookingId;
    private UUID paymentId;
    private List<UUID> seatIds;
    private Integer bookedCount;
    private String confirmedAt;
}
