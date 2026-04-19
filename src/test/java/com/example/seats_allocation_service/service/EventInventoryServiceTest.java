package com.example.seats_allocation_service.service;

import com.example.seats_allocation_service.dtos.InventoryInitRequestedEvent;
import com.example.seats_allocation_service.dtos.InventoryInitRequest;
import com.example.seats_allocation_service.dtos.InventoryPricing;
import com.example.seats_allocation_service.exceptions.EventInventoryAlreadyExistsException;
import com.example.seats_allocation_service.exceptions.IdempotencyConflictException;
import com.example.seats_allocation_service.models.AllocationIdempotency;
import com.example.seats_allocation_service.repository.AllocationIdempotencyRepository;
import com.example.seats_allocation_service.models.EventInventoryContext;
import com.example.seats_allocation_service.repository.EventInventoryContextRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventInventoryServiceTest {

    @Mock
    private EventInventoryContextRepository eventInventoryContextRepository;

    @Mock
    private EventSeatService eventSeatService;

    @Mock
    private AllocationIdempotencyRepository allocationIdempotencyRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private EventInventoryService eventInventoryService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        eventInventoryService = new EventInventoryService(
                eventInventoryContextRepository,
                allocationIdempotencyRepository,
                eventSeatService,
                stringRedisTemplate,
                objectMapper,
                "USD"
        );
        lenient().when(allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey(anyString(), any(UUID.class), anyString()))
                .thenReturn(java.util.Optional.empty());
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
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
        when(eventInventoryContextRepository.insertContext(eventId, venueId, "USD")).thenReturn(1);

        EventInventoryContext result = eventInventoryService.initializeInventory(eventId, request);

        verify(eventInventoryContextRepository).insertContext(eventId, venueId, "USD");

        assertNotNull(result);
        assertEquals(eventId, result.getId());
        assertEquals(venueId, result.getVenueId());
        assertEquals("USD", result.getCurrency());
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

        when(eventInventoryContextRepository.existsById(eventId)).thenReturn(false);
        when(eventInventoryContextRepository.insertContext(eventId, venueId, "INR")).thenReturn(1);

        EventInventoryContext result = eventInventoryService.initializeInventory(eventId, request);

        assertEquals(eventId, result.getId());
        assertEquals(venueId, result.getVenueId());
        assertEquals("INR", result.getCurrency());
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

        verify(eventInventoryContextRepository, never()).insertContext(any(UUID.class), any(UUID.class), anyString());
        verify(eventSeatService, never()).initializeInventory(any(UUID.class), any());
    }

    @Test
    void initializeInventory_whenKafkaEventIsReceived_mapsToDefaultCurrencyRequest() {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();
        InventoryInitRequestedEvent event = new InventoryInitRequestedEvent();
        event.setEventId(eventId);
        event.setVenueId(venueId);
        event.setPublishedAt(java.time.Instant.parse("2026-04-12T10:15:30Z"));
        UUID sectionId = UUID.randomUUID();
        UUID venueSeatId = UUID.randomUUID();
        InventoryInitRequestedEvent.SectionPrice sectionPrice = new InventoryInitRequestedEvent.SectionPrice();
        sectionPrice.setSectionId(sectionId);
        sectionPrice.setPriceCents(1200);
        sectionPrice.setCurrency("INR");
        event.setSectionPrices(java.util.List.of(sectionPrice));
        InventoryInitRequestedEvent.SeatInventory seat = new InventoryInitRequestedEvent.SeatInventory();
        seat.setVenueSeatId(venueSeatId);
        seat.setSectionId(sectionId);
        seat.setPriceCents(1200);
        seat.setCurrency("INR");
        event.setSeats(java.util.List.of(seat));

        when(eventInventoryContextRepository.existsById(eventId)).thenReturn(false);
        when(eventInventoryContextRepository.insertContext(eventId, venueId, "INR")).thenReturn(1);

        EventInventoryContext result = eventInventoryService.initializeInventory(event);

        assertNotNull(result);
        assertEquals(eventId, result.getId());
        assertEquals(venueId, result.getVenueId());
        assertEquals("INR", result.getCurrency());
        verify(eventSeatService).initializeInventory(any(UUID.class), any());
    }

    @Test
    void initializeInventory_whenRequestIdAlreadyProcessed_replaysStoredResponse() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();
        InventoryInitRequestedEvent event = new InventoryInitRequestedEvent();
        event.setEventId(eventId);
        event.setVenueId(venueId);
        event.setPublishedAt(java.time.Instant.parse("2026-04-12T10:15:30Z"));
        UUID sectionId = UUID.randomUUID();
        UUID venueSeatId = UUID.randomUUID();
        InventoryInitRequestedEvent.SectionPrice sectionPrice = new InventoryInitRequestedEvent.SectionPrice();
        sectionPrice.setSectionId(sectionId);
        sectionPrice.setPriceCents(1500);
        sectionPrice.setCurrency("USD");
        event.setSectionPrices(java.util.List.of(sectionPrice));
        InventoryInitRequestedEvent.SeatInventory seat = new InventoryInitRequestedEvent.SeatInventory();
        seat.setVenueSeatId(venueSeatId);
        seat.setSectionId(sectionId);
        seat.setPriceCents(1500);
        seat.setCurrency("USD");
        event.setSeats(java.util.List.of(seat));

        EventInventoryContext stored = new EventInventoryContext();
        stored.setId(eventId);
        stored.setVenueId(venueId);
        stored.setCurrency("USD");

        AllocationIdempotency idempotency = new AllocationIdempotency();
        idempotency.setOperationType("INVENTORY_INIT");
        idempotency.setResourceId(eventId);
        idempotency.setIdempotencyKey(event.getRequestId());
        idempotency.setPayloadHash(hashInventoryPayload(event));
        idempotency.setResponsePayload(objectMapper.writeValueAsString(stored));
        when(allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey("INVENTORY_INIT", eventId, event.getRequestId()))
                .thenReturn(java.util.Optional.of(idempotency));

        EventInventoryContext result = eventInventoryService.initializeInventory(event);

        assertEquals(stored.getId(), result.getId());
        assertEquals(stored.getVenueId(), result.getVenueId());
        assertEquals(stored.getCurrency(), result.getCurrency());
        verify(eventInventoryContextRepository, never()).insertContext(any(UUID.class), any(UUID.class), anyString());
        verify(eventSeatService, never()).initializeInventory(any(UUID.class), any());
    }

    @Test
    void initializeInventory_whenRedisReplayExists_returnsCachedResponse() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();
        InventoryInitRequestedEvent event = new InventoryInitRequestedEvent();
        event.setEventId(eventId);
        event.setVenueId(venueId);
        event.setPublishedAt(java.time.Instant.parse("2026-04-12T10:15:30Z"));
        UUID sectionId = UUID.randomUUID();
        UUID venueSeatId = UUID.randomUUID();
        InventoryInitRequestedEvent.SectionPrice sectionPrice = new InventoryInitRequestedEvent.SectionPrice();
        sectionPrice.setSectionId(sectionId);
        sectionPrice.setPriceCents(1500);
        sectionPrice.setCurrency("USD");
        event.setSectionPrices(java.util.List.of(sectionPrice));
        InventoryInitRequestedEvent.SeatInventory seat = new InventoryInitRequestedEvent.SeatInventory();
        seat.setVenueSeatId(venueSeatId);
        seat.setSectionId(sectionId);
        seat.setPriceCents(1500);
        seat.setCurrency("USD");
        event.setSeats(java.util.List.of(seat));

        EventInventoryContext stored = new EventInventoryContext();
        stored.setId(eventId);
        stored.setVenueId(venueId);
        stored.setCurrency("USD");

        String cachedPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "payloadHash", hashInventoryPayload(event),
                "responsePayload", objectMapper.writeValueAsString(stored)
        ));
        when(valueOperations.get("inventory:init:response:" + eventId + ":" + event.getRequestId())).thenReturn(cachedPayload);

        EventInventoryContext result = eventInventoryService.initializeInventory(event);

        assertEquals(stored.getId(), result.getId());
        assertEquals(stored.getVenueId(), result.getVenueId());
        assertEquals(stored.getCurrency(), result.getCurrency());
        verify(allocationIdempotencyRepository, never()).findByOperationTypeAndResourceIdAndIdempotencyKey(anyString(), any(UUID.class), anyString());
        verify(eventInventoryContextRepository, never()).insertContext(any(UUID.class), any(UUID.class), anyString());
    }

    @Test
    void initializeInventory_whenRequestIdIsReusedWithDifferentPayload_throwsConflict() {
        UUID eventId = UUID.randomUUID();
        InventoryInitRequestedEvent event = new InventoryInitRequestedEvent();
        event.setEventId(eventId);
        event.setVenueId(UUID.randomUUID());
        event.setPublishedAt(java.time.Instant.parse("2026-04-12T10:15:30Z"));
        UUID sectionId = UUID.randomUUID();
        UUID venueSeatId = UUID.randomUUID();
        InventoryInitRequestedEvent.SectionPrice sectionPrice = new InventoryInitRequestedEvent.SectionPrice();
        sectionPrice.setSectionId(sectionId);
        sectionPrice.setPriceCents(1500);
        sectionPrice.setCurrency("USD");
        event.setSectionPrices(java.util.List.of(sectionPrice));
        InventoryInitRequestedEvent.SeatInventory seat = new InventoryInitRequestedEvent.SeatInventory();
        seat.setVenueSeatId(venueSeatId);
        seat.setSectionId(sectionId);
        seat.setPriceCents(1500);
        seat.setCurrency("USD");
        event.setSeats(java.util.List.of(seat));

        AllocationIdempotency idempotency = new AllocationIdempotency();
        idempotency.setOperationType("INVENTORY_INIT");
        idempotency.setResourceId(eventId);
        idempotency.setIdempotencyKey(event.getRequestId());
        idempotency.setPayloadHash("different-hash");
        idempotency.setResponsePayload("{}");
        when(allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey("INVENTORY_INIT", eventId, event.getRequestId()))
                .thenReturn(java.util.Optional.of(idempotency));

        assertThrows(IdempotencyConflictException.class, () -> eventInventoryService.initializeInventory(event));
        verify(eventInventoryContextRepository, never()).insertContext(any(UUID.class), any(UUID.class), anyString());
        verify(eventSeatService, never()).initializeInventory(any(UUID.class), any());
    }

    private String hashInventoryPayload(InventoryInitRequestedEvent event) {
        String seats = event.getSeats() == null ? "" : event.getSeats().stream()
                .map(seat -> seat.getVenueSeatId() + ":" + seat.getSectionId() + ":" + seat.getPriceCents() + ":" + seat.getCurrency())
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        return hash(event.getEventId(), event.getVenueId(), event.getPublishedAt(), seats);
    }

    private String hash(Object... values) {
        String canonical = java.util.Arrays.stream(values)
                .map(value -> value == null ? "null" : value.toString())
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
