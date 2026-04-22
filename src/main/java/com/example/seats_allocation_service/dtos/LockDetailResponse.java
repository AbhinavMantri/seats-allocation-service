package com.example.seats_allocation_service.dtos;

import com.example.seats_allocation_service.dtos.common.ApiResponse;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class LockDetailResponse extends ApiResponse {
    private LockDetail result;
}
