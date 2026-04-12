package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.dtos.InventoryInitializedEvent;
import com.example.seats_allocation_service.dtos.InventoryInitRequestedEvent;
import com.example.seats_allocation_service.exceptions.EventInventoryAlreadyExistsException;
import com.example.seats_allocation_service.service.EventInventoryService;
import com.example.seats_allocation_service.service.EventSeatService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalEventInventoryControllerTest {

    @Mock
    private EventInventoryService eventInventoryService;

    @Mock
    private EventSeatService eventSeatService;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private InternalEventInventoryController internalEventInventoryController;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        internalEventInventoryController = new InternalEventInventoryController(
                eventInventoryService,
                eventSeatService,
                kafkaTemplate,
                objectMapper
        );
        ReflectionTestUtils.setField(internalEventInventoryController, "inventoryInitResultTopic", "inventory-published.v1");
    }

    @Test
    void initializeInventory_whenPayloadIsValid_callsServiceAndPublishesSuccessEvent() throws Exception {
        InventoryInitRequestedEvent event = event();
        String payload = objectMapper.writeValueAsString(event);
        when(eventSeatService.getSeatCount(event.getEventId())).thenReturn(12);

        internalEventInventoryController.initializeInventory(payload);

        verify(eventInventoryService).initializeInventory(event);
        verify(eventSeatService).getSeatCount(event.getEventId());
        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(
                org.mockito.ArgumentMatchers.eq("inventory-published.v1"),
                org.mockito.ArgumentMatchers.eq(event.getEventId().toString()),
                payloadCaptor.capture()
        );
        InventoryInitializedEvent published = objectMapper.readValue(payloadCaptor.getValue(), InventoryInitializedEvent.class);
        assertEquals(event.getRequestId(), published.getRequestId());
        assertEquals(event.getEventId(), published.getEventId());
        assertEquals("SUCCESS", published.getStatus());
        assertEquals(12, published.getTotalSeats());
        assertNull(published.getSeatMapVersion());
        assertNotNull(published.getProcessedAt());
    }

    @Test
    void initializeInventory_whenInventoryAlreadyExists_doesNotThrow() throws Exception {
        InventoryInitRequestedEvent event = event();
        String payload = objectMapper.writeValueAsString(event);
        doThrow(new EventInventoryAlreadyExistsException("already exists"))
                .when(eventInventoryService)
                .initializeInventory(event);

        assertDoesNotThrow(() -> internalEventInventoryController.initializeInventory(payload));
        verify(eventInventoryService).initializeInventory(event);
        verify(kafkaTemplate, never()).send(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void initializeInventory_whenPayloadIsInvalid_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> internalEventInventoryController.initializeInventory("{invalid-json")
        );
    }

    private InventoryInitRequestedEvent event() {
        InventoryInitRequestedEvent event = new InventoryInitRequestedEvent();
        event.setEventId(UUID.randomUUID());
        event.setVenueId(UUID.randomUUID());
        event.setEventType("EVENT_PUBLISHED");
        InventoryInitRequestedEvent.SectionPrice sectionPrice = new InventoryInitRequestedEvent.SectionPrice();
        sectionPrice.setSectionId(UUID.randomUUID());
        sectionPrice.setSectionName("VIP");
        sectionPrice.setSortOrder(1);
        sectionPrice.setPriceCents(2500);
        sectionPrice.setCurrency("INR");
        event.setSectionPrices(java.util.List.of(sectionPrice));
        InventoryInitRequestedEvent.SeatInventory seat = new InventoryInitRequestedEvent.SeatInventory();
        seat.setEventSeatId(UUID.randomUUID());
        seat.setVenueSeatId(UUID.randomUUID());
        seat.setSectionId(sectionPrice.getSectionId());
        seat.setSeatCode("VIP-R01-S01");
        seat.setRowLabel("R01");
        seat.setSeatNumber(1);
        seat.setPriceCents(2500);
        seat.setCurrency("INR");
        event.setSeats(java.util.List.of(seat));
        return event;
    }
}
