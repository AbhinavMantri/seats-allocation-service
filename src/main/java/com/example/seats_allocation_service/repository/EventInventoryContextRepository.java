package com.example.seats_allocation_service.repository;

import com.example.seats_allocation_service.models.EventInventoryContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface EventInventoryContextRepository extends JpaRepository<EventInventoryContext, UUID> {
    @Modifying
    @Query(value = """
            INSERT INTO event_inventory_context (event_id, venue_id, currency, status, created_at, updated_at)
            VALUES (:eventId, :venueId, :currency, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """, nativeQuery = true)
    int insertContext(
            @Param("eventId") UUID eventId,
            @Param("venueId") UUID venueId,
            @Param("currency") String currency
    );
}
