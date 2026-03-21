package com.example.seats_allocation_service.dtos;

import java.util.List;
import java.util.UUID;

import lombok.Data;

@Data
public class ReleaseSeatsResult {
    private UUID eventId;
    private UUID bookingId;
    private List<UUID> seatIds;
    private Integer releasedCount;
}
