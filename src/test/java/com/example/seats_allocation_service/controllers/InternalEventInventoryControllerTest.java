package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.dtos.InventoryInitRequest;
import com.example.seats_allocation_service.dtos.InventoryInitResponse;
import com.example.seats_allocation_service.dtos.common.ResponseStatus;
import com.example.seats_allocation_service.exceptions.EventInventoryAlreadyExistsException;
import com.example.seats_allocation_service.models.EventInventoryContext;
import com.example.seats_allocation_service.service.EventInventoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalEventInventoryControllerTest {

    @Mock
    private EventInventoryService eventInventoryService;

    @InjectMocks
    private InternalEventInventoryController internalEventInventoryController;

    @Test
    void initializeInventory_whenSuccessful_returnsOkWithSuccessPayload() {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();
        InventoryInitRequest request = new InventoryInitRequest();

        EventInventoryContext savedContext = new EventInventoryContext();
        savedContext.setId(eventId);
        savedContext.setVenueId(venueId);
        savedContext.setCurrency("USD");

        when(eventInventoryService.initializeInventory(eventId, request)).thenReturn(savedContext);

        ResponseEntity<InventoryInitResponse> response = internalEventInventoryController.initializeInventory(eventId, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.SUCCESS, response.getBody().getStatus());
        assertEquals("Event initiated successfully", response.getBody().getMessage());
        assertEquals(savedContext, response.getBody().getEventInventoryContext());
        verify(eventInventoryService).initializeInventory(eventId, request);
    }

    @Test
    void initializeInventory_whenInventoryAlreadyExists_returnsConflictWithFailurePayload() {
        UUID eventId = UUID.randomUUID();
        InventoryInitRequest request = new InventoryInitRequest();

        when(eventInventoryService.initializeInventory(eventId, request))
                .thenThrow(new EventInventoryAlreadyExistsException("already exists"));

        ResponseEntity<InventoryInitResponse> response = internalEventInventoryController.initializeInventory(eventId, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("already exists", response.getBody().getMessage());
        verify(eventInventoryService).initializeInventory(eventId, request);
    }
}
