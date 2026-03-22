package com.example.seats_allocation_service.service;

import com.example.seats_allocation_service.dtos.InventoryInitRequestedEvent;
import com.example.seats_allocation_service.dtos.InventoryInitRequest;
import com.example.seats_allocation_service.dtos.InventoryPricing;
import com.example.seats_allocation_service.exceptions.EventInventoryAlreadyExistsException;
import com.example.seats_allocation_service.models.EventInventoryContext;
import com.example.seats_allocation_service.repository.EventInventoryContextRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventInventoryServiceTest {

    @Mock
    private EventInventoryContextRepository eventInventoryContextRepository;

    @Mock
    private EventSeatService eventSeatService;

    private EventInventoryService eventInventoryService;

    @BeforeEach
    void setUp() {
        eventInventoryService = new EventInventoryService(
                eventInventoryContextRepository,
                eventSeatService,
                "USD"
        );
    }

    @Test
    void initializeInventory_whenRequestContainsPricing_savesContextAndInitializesSeats() {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();

        InventoryPricing pricing = new InventoryPricing();
        pricing.setSectionId(UUID.randomUUID());
        pricing.setPriceCents(1000L);

        InventoryInitRequest request = new InventoryInitRequest();
        request.setVenueId(venueId);
        request.setCurrency("USD");
        request.setPricing(Set.of(pricing));

        when(eventInventoryContextRepository.existsById(eventId)).thenReturn(false);
        when(eventInventoryContextRepository.save(any(EventInventoryContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EventInventoryContext result = eventInventoryService.initializeInventory(eventId, request);

        ArgumentCaptor<EventInventoryContext> contextCaptor = ArgumentCaptor.forClass(EventInventoryContext.class);
        verify(eventInventoryContextRepository).save(contextCaptor.capture());
        EventInventoryContext saved = contextCaptor.getValue();
        assertEquals(eventId, saved.getId());
        assertEquals(venueId, saved.getVenueId());
        assertEquals("USD", saved.getCurrency());

        assertNotNull(result);
        assertEquals(eventId, result.getId());
        verify(eventSeatService).initializeInventory(eventId, request.getPricing());
    }

    @Test
    void initializeInventory_whenPricingIsNull_savesContextAndSkipsSeatInitialization() {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();

        InventoryInitRequest request = new InventoryInitRequest();
        request.setVenueId(venueId);
        request.setCurrency("INR");
        request.setPricing(null);

        EventInventoryContext persisted = new EventInventoryContext();
        persisted.setId(eventId);
        persisted.setVenueId(venueId);
        persisted.setCurrency("INR");

        when(eventInventoryContextRepository.existsById(eventId)).thenReturn(false);
        when(eventInventoryContextRepository.save(any(EventInventoryContext.class))).thenReturn(persisted);

        EventInventoryContext result = eventInventoryService.initializeInventory(eventId, request);

        assertSame(persisted, result);
        verify(eventSeatService, never()).initializeInventory(any(UUID.class), any());
    }

    @Test
    void initializeInventory_whenContextAlreadyExists_throwsConflictAndDoesNotPersist() {
        UUID eventId = UUID.randomUUID();
        InventoryInitRequest request = new InventoryInitRequest();

        when(eventInventoryContextRepository.existsById(eventId)).thenReturn(true);

        assertThrows(
                EventInventoryAlreadyExistsException.class,
                () -> eventInventoryService.initializeInventory(eventId, request)
        );

        verify(eventInventoryContextRepository, never()).save(any(EventInventoryContext.class));
        verify(eventSeatService, never()).initializeInventory(any(UUID.class), any());
    }

    @Test
    void initializeInventory_whenKafkaEventIsReceived_mapsToDefaultCurrencyRequest() {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();
        InventoryInitRequestedEvent event = new InventoryInitRequestedEvent();
        event.setEventId(eventId);
        event.setVenueId(venueId);
        event.setRequestId("req-init-001");

        when(eventInventoryContextRepository.existsById(eventId)).thenReturn(false);
        when(eventInventoryContextRepository.save(any(EventInventoryContext.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EventInventoryContext result = eventInventoryService.initializeInventory(event);

        assertNotNull(result);
        assertEquals(eventId, result.getId());
        assertEquals(venueId, result.getVenueId());
        assertEquals("USD", result.getCurrency());
        verify(eventSeatService, never()).initializeInventory(any(UUID.class), any());
    }
}
