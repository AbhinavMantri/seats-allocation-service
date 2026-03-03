package com.example.seats_allocation_service.repository;

import com.example.seats_allocation_service.models.EventInventoryContext;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EventInventoryContextRepository extends JpaRepository<EventInventoryContext, UUID> {
}
