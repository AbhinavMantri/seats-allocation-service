package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.dtos.LockDetail;
import com.example.seats_allocation_service.dtos.LockDetailResponse;
import com.example.seats_allocation_service.dtos.common.ResponseStatus;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.service.LockService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LockControllerTest {

    @Mock
    private LockService lockService;

    @InjectMocks
    private LockController lockController;

    @Test
    void getLockDetails_whenLockExists_returnsOkResponse() {
        UUID bookingId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        LockDetail lockDetail = LockDetail.builder()
                .bookingId(bookingId)
                .eventId(eventId)
                .seatIds(List.of(seatId))
                .lockExpiresAt("2026-03-21T10:15:30Z")
                .status("LOCKED")
                .build();
        when(lockService.getLockDetails(bookingId)).thenReturn(lockDetail);

        ResponseEntity<LockDetailResponse> response = lockController.getLockDetails(bookingId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.SUCCESS, response.getBody().getStatus());
        assertEquals("Lock details fetched successfully", response.getBody().getMessage());
        assertEquals(lockDetail, response.getBody().getResult());
        verify(lockService).getLockDetails(bookingId);
    }

    @Test
    void getLockDetails_whenLockIsMissing_returnsNotFoundResponse() {
        UUID bookingId = UUID.randomUUID();
        when(lockService.getLockDetails(bookingId))
                .thenThrow(new SeatsNotFoundException("No active lock found for bookingId: " + bookingId));

        ResponseEntity<LockDetailResponse> response = lockController.getLockDetails(bookingId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("No active lock found for bookingId: " + bookingId, response.getBody().getMessage());
    }
}
