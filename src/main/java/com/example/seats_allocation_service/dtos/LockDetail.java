package com.example.seats_allocation_service.dtos;

import java.util.List;
import java.util.UUID;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LockDetail {
    private UUID bookingId;
    private UUID eventId;
    private List<LockedSeatDetail> seats;
    private Long totalAmountMinor;
    private String currency;
    private String lockExpiresAt;
    private String status;
}
