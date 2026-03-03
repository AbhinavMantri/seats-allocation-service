package com.example.seats_allocation_service.repository;

import com.example.seats_allocation_service.models.EventSeat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface EventSeatRepository extends JpaRepository<EventSeat, UUID> {
    List<EventSeat> findByEventId(UUID eventId);
}
