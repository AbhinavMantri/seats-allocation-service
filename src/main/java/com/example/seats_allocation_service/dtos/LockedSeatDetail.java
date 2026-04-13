package com.example.seats_allocation_service.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LockedSeatDetail {
    private UUID eventSeatId;
    private UUID sectionId;
    private Integer priceCents;
}
