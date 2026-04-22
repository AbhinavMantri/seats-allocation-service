package com.example.seats_allocation_service.dtos;

import com.example.seats_allocation_service.dtos.common.ApiResponse;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = false)
public class ReleaseSeatsResponse extends ApiResponse {
    private ReleaseSeatsResult result;
}
