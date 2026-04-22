package com.example.seats_allocation_service.dtos;

import lombok.Data;

import java.util.Set;
import java.util.UUID;

@Data
public class InventoryInitRequest {
    private UUID venueId;
    private String currency;
    private Set<InventoryPricing> pricing;
}