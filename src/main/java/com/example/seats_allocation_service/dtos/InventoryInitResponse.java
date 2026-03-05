package com.example.seats_allocation_service.dtos;

import com.example.seats_allocation_service.dtos.common.ApiResponse;
import com.example.seats_allocation_service.models.EventInventoryContext;
import lombok.Data;

@Data
public class InventoryInitResponse extends ApiResponse {
    private EventInventoryContext eventInventoryContext;
}
