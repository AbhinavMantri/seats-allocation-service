package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.models.EventInventoryContext;
import com.example.seats_allocation_service.repository.EventInventoryContextRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/internal/events/{eventId}")
@RequiredArgsConstructor
public class InternalEventInventoryController {

    private final EventInventoryContextRepository eventInventoryContextRepository;

    @PostMapping("/inventory/init")
    public ResponseEntity<EventInventoryContext> initializeInventory(
            @PathVariable UUID eventId,
            @RequestBody InventoryInitRequest request
    ) {
        // TODO: implement inventory initialization logic to set up event inventory context with venue and currency details
        EventInventoryContext context = eventInventoryContextRepository
                .findById(eventId)
                .orElseGet(EventInventoryContext::new);

        context.setId(eventId);
        context.setVenueId(request.venueId());
        context.setCurrency(request.currency());

        return ResponseEntity.ok(eventInventoryContextRepository.save(context));
    }

    public record InventoryInitRequest(UUID venueId, String currency) {
    }
}
