package com.example.seats_allocation_service.dtos;

import lombok.Data;

import java.util.UUID;

@Data
public class InventoryPricing {
    private UUID sectionId;
    private Long priceCents;
}
