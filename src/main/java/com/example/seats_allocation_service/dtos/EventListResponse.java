package com.example.seats_allocation_service.dtos;

import java.util.List;

import com.example.seats_allocation_service.dtos.common.ApiResponse;
import com.example.seats_allocation_service.models.EventSeat;

import lombok.Data;

@Data
public class EventListResponse extends ApiResponse {
    private List<EventSeat> seats;
}
