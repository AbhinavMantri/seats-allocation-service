package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.dtos.EventListResponse;
import com.example.seats_allocation_service.dtos.LockSeatResponse;
import com.example.seats_allocation_service.dtos.LockSeatsRequest;
import com.example.seats_allocation_service.dtos.ReleaseLocksRequest;
import com.example.seats_allocation_service.dtos.SeatAvailabilityResponse;
import com.example.seats_allocation_service.dtos.common.ApiResponse;
import com.example.seats_allocation_service.dtos.common.ResponseStatus;
import com.example.seats_allocation_service.exceptions.EventNotFoundException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.models.EventSeat;
import com.example.seats_allocation_service.service.EventSeatService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventSeatsControllerTest {

    @Mock
    private EventSeatService eventSeatService;

    @InjectMocks
    private EventSeatsController eventSeatsController;

    @Test
    void getSeats_whenSeatsExist_returnsOkResponse() {
        UUID eventId = UUID.randomUUID();
        EventSeat seat = seat(eventId, UUID.randomUUID());
        when(eventSeatService.getSeats(eventId)).thenReturn(List.of(seat));

        ResponseEntity<EventListResponse> response = eventSeatsController.getSeats(eventId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.SUCCESS, response.getBody().getStatus());
        assertEquals("Seat availability fetched successfully", response.getBody().getMessage());
        assertEquals(List.of(seat), response.getBody().getSeats());
        verify(eventSeatService).getSeats(eventId);
    }

    @Test
    void getSeats_whenEventDoesNotExist_returnsNotFound() {
        UUID eventId = UUID.randomUUID();
        when(eventSeatService.getSeats(eventId)).thenThrow(new EventNotFoundException("missing event"));

        ResponseEntity<EventListResponse> response = eventSeatsController.getSeats(eventId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("missing event", response.getBody().getMessage());
    }

    @Test
    void getAvailabilitySummary_whenEventExists_returnsOkResponse() {
        UUID eventId = UUID.randomUUID();
        SeatAvailabilityResponse serviceResponse = new SeatAvailabilityResponse();
        serviceResponse.setTotalSeats(100);
        serviceResponse.setAvailableSeats(70);
        serviceResponse.setLockedSeats(20);
        when(eventSeatService.getAvailabilitySummary(eventId)).thenReturn(serviceResponse);

        ResponseEntity<SeatAvailabilityResponse> response = eventSeatsController.getAvailabilitySummary(eventId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.SUCCESS, response.getBody().getStatus());
        assertEquals("Seat availability summary fetched successfully", response.getBody().getMessage());
        assertEquals(100, response.getBody().getTotalSeats());
        assertEquals(70, response.getBody().getAvailableSeats());
        assertEquals(20, response.getBody().getLockedSeats());
        verify(eventSeatService).getAvailabilitySummary(eventId);
    }

    @Test
    void getAvailabilitySummary_whenEventDoesNotExist_returnsNotFound() {
        UUID eventId = UUID.randomUUID();
        when(eventSeatService.getAvailabilitySummary(eventId)).thenThrow(new EventNotFoundException("missing event"));

        ResponseEntity<SeatAvailabilityResponse> response = eventSeatsController.getAvailabilitySummary(eventId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("missing event", response.getBody().getMessage());
    }

    @Test
    void lockSeats_whenSuccessful_returnsOkResponse() {
        UUID eventId = UUID.randomUUID();
        LockSeatsRequest request = lockRequest();

        ResponseEntity<LockSeatResponse> response = eventSeatsController.lockSeats(eventId, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.SUCCESS, response.getBody().getStatus());
        assertEquals("Seats locked successfully", response.getBody().getMessage());
        verify(eventSeatService).lockSeats(eventId, request.getIdempotencyKey(), request.getUserId(), request.getSeatIds());
    }

    @Test
    void lockSeats_whenSeatConflictOccurs_returnsConflict() {
        UUID eventId = UUID.randomUUID();
        LockSeatsRequest request = lockRequest();
        doThrow(new SeatLockConflictException("seat locked"))
                .when(eventSeatService)
                .lockSeats(eventId, request.getIdempotencyKey(), request.getUserId(), request.getSeatIds());

        ResponseEntity<LockSeatResponse> response = eventSeatsController.lockSeats(eventId, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("seat locked", response.getBody().getMessage());
    }

    @Test
    void releaseLocks_whenSuccessful_returnsReleasedCount() {
        UUID eventId = UUID.randomUUID();
        ReleaseLocksRequest request = releaseRequest();
        when(eventSeatService.releaseLocks(eventId, request.getUserId(), request.getSeatIds())).thenReturn(2);

        ResponseEntity<ApiResponse> response = eventSeatsController.releaseLocks(eventId, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.SUCCESS, response.getBody().getStatus());
        assertEquals("Released 2 seat lock(s)", response.getBody().getMessage());
    }

    @Test
    void releaseLocks_whenSeatsDoNotExist_returnsNotFound() {
        UUID eventId = UUID.randomUUID();
        ReleaseLocksRequest request = releaseRequest();
        when(eventSeatService.releaseLocks(eventId, request.getUserId(), request.getSeatIds()))
                .thenThrow(new SeatsNotFoundException("missing seats"));

        ResponseEntity<ApiResponse> response = eventSeatsController.releaseLocks(eventId, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("missing seats", response.getBody().getMessage());
    }

    private LockSeatsRequest lockRequest() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(UUID.randomUUID());
        request.setIdempotencyKey("idem-key");
        request.setSeatIds(List.of(UUID.randomUUID(), UUID.randomUUID()));
        return request;
    }

    private ReleaseLocksRequest releaseRequest() {
        ReleaseLocksRequest request = new ReleaseLocksRequest();
        request.setUserId(UUID.randomUUID());
        request.setSeatIds(List.of(UUID.randomUUID(), UUID.randomUUID()));
        return request;
    }

    private EventSeat seat(UUID eventId, UUID seatId) {
        EventSeat seat = new EventSeat();
        seat.setId(seatId);
        seat.setEventId(eventId);
        seat.setVenueSeatId(UUID.randomUUID());
        seat.setSectionId(UUID.randomUUID());
        seat.setPriceCents(1500);
        seat.setStatus(EventSeat.SeatStatus.AVAILABLE);
        return seat;
    }
}
