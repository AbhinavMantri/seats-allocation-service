package com.example.seats_allocation_service.dtos.common;

import lombok.Data;

@Data
public class ApiResponse {
    private ResponseStatus status;
    private String message;
}
