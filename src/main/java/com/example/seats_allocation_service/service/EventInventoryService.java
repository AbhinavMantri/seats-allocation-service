package com.example.seats_allocation_service.service;

import com.example.seats_allocation_service.dtos.InventoryPricing;
import com.example.seats_allocation_service.dtos.InventoryInitRequest;
import com.example.seats_allocation_service.dtos.InventoryInitRequestedEvent;
import com.example.seats_allocation_service.exceptions.EventInventoryAlreadyExistsException;
import com.example.seats_allocation_service.models.EventInventoryContext;
import com.example.seats_allocation_service.repository.EventInventoryContextRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class EventInventoryService {
    private final EventInventoryContextRepository eventInventoryContextRepository;

    private final EventSeatService eventSeatService;
    private final String defaultCurrency;

    @Autowired
    public EventInventoryService(
            EventInventoryContextRepository eventInventoryContextRepository,
            EventSeatService eventSeatService,
            @Value("${app.inventory.default-currency:USD}") String defaultCurrency
    ) {
        this.eventInventoryContextRepository = eventInventoryContextRepository;
        this.eventSeatService = eventSeatService;
        this.defaultCurrency = defaultCurrency;
    }

    public EventInventoryContext initializeInventory(InventoryInitRequestedEvent event) throws EventInventoryAlreadyExistsException {
        InventoryInitRequest request = new InventoryInitRequest();
        request.setVenueId(event.getVenueId());
        request.setCurrency(defaultCurrency);
        request.setPricing(Set.<InventoryPricing>of());
        return initializeInventory(event.getEventId(), request);
    }

    @Transactional
    public EventInventoryContext initializeInventory(UUID eventId, InventoryInitRequest request) throws EventInventoryAlreadyExistsException {
        try (MDC.MDCCloseable serviceLogGroup = MDC.putCloseable("logGroup", "internal-event-inventory-init")) {
            log.info("initializeInventory started for eventId={}", eventId);
            if (eventInventoryContextRepository.existsById(eventId)) {
                throw new EventInventoryAlreadyExistsException("Event inventory context already exists for eventId: " + eventId);
            }
            EventInventoryContext eventInventoryContext = new EventInventoryContext();
            eventInventoryContext.setId(eventId);
            eventInventoryContext.setVenueId(request.getVenueId());
            eventInventoryContext.setCurrency(request.getCurrency());
            EventInventoryContext savedContext = eventInventoryContextRepository.save(eventInventoryContext);

            Set<InventoryPricing> pricing = request.getPricing();
            if (pricing != null && !pricing.isEmpty()) {
                eventSeatService.initializeInventory(eventId, pricing);
            }

            log.info("initializeInventory completed for eventId={}", eventId);
            return savedContext;
        }
    }
}
