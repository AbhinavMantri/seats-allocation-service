package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.dtos.LockSeatResponse;
import com.example.seats_allocation_service.dtos.LockSeatsRequest;
import com.example.seats_allocation_service.dtos.ReleaseLocksRequest;
import com.example.seats_allocation_service.dtos.ReleaseReason;
import com.example.seats_allocation_service.dtos.ReleaseSeatsRequest;
import com.example.seats_allocation_service.dtos.ReleaseSeatsResponse;
import com.example.seats_allocation_service.dtos.ReleaseSeatsResult;
import com.example.seats_allocation_service.dtos.SeatConfirmRequest;
import com.example.seats_allocation_service.dtos.SeatConfirmResponse;
import com.example.seats_allocation_service.dtos.SeatsConfirmation;
import com.example.seats_allocation_service.dtos.common.ApiResponse;
import com.example.seats_allocation_service.dtos.common.ResponseStatus;
import com.example.seats_allocation_service.exceptions.EventNotFoundException;
import com.example.seats_allocation_service.exceptions.IdempotencyConflictException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.service.InternalSeatsService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalSeatsControllerTest {

    @Mock
    private InternalSeatsService internalSeatsService;

    @InjectMocks
    private InternalSeatsController internalSeatsController;

    @Test
    void lockSeats_whenSuccessful_returnsOkResponse() {
        UUID eventId = UUID.randomUUID();
        LockSeatsRequest request = lockRequest();

        ResponseEntity<LockSeatResponse> response = internalSeatsController.lockSeats(eventId, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.SUCCESS, response.getBody().getStatus());
        assertEquals("Seats locked successfully", response.getBody().getMessage());
        verify(internalSeatsService).lockSeats(eventId, request.getIdempotencyKey(), request.getUserId(), request.getSeatIds());
    }

    @Test
    void lockSeats_whenSeatConflictOccurs_returnsConflict() {
        UUID eventId = UUID.randomUUID();
        LockSeatsRequest request = lockRequest();
        doThrow(new SeatLockConflictException("seat locked"))
                .when(internalSeatsService)
                .lockSeats(eventId, request.getIdempotencyKey(), request.getUserId(), request.getSeatIds());

        ResponseEntity<LockSeatResponse> response = internalSeatsController.lockSeats(eventId, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("seat locked", response.getBody().getMessage());
    }

    @Test
    void confirmSeats_whenSuccessful_returnsOkResponse() {
        String idempotencyKey = "idem-confirm";
        SeatConfirmRequest request = confirmRequest();
        SeatsConfirmation confirmation = SeatsConfirmation.builder()
                .eventId(request.getEventId())
                .bookingId(request.getBookingId())
                .paymentId(request.getPaymentId())
                .seatIds(request.getSeatIds())
                .bookedCount(request.getSeatIds().size())
                .confirmedAt(request.getConfirmedAt().toString())
                .build();
        when(internalSeatsService.confirmSeats(
                idempotencyKey,
                request.getEventId(),
                request.getBookingId(),
                request.getPaymentId(),
                request.getSeatIds(),
                request.getConfirmedAt()
        )).thenReturn(confirmation);

        ResponseEntity<SeatConfirmResponse> response = internalSeatsController.confirmSeats(idempotencyKey, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.SUCCESS, response.getBody().getStatus());
        assertEquals("Confirmed " + request.getSeatIds().size() + " seat(s)", response.getBody().getMessage());
        assertEquals(confirmation, response.getBody().getSeatConfirmation());
    }

    @Test
    void confirmSeats_whenEventMissing_returnsNotFound() {
        String idempotencyKey = "idem-confirm";
        SeatConfirmRequest request = confirmRequest();
        when(internalSeatsService.confirmSeats(
                idempotencyKey,
                request.getEventId(),
                request.getBookingId(),
                request.getPaymentId(),
                request.getSeatIds(),
                request.getConfirmedAt()
        )).thenThrow(new EventNotFoundException("missing event"));

        ResponseEntity<SeatConfirmResponse> response = internalSeatsController.confirmSeats(idempotencyKey, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("missing event", response.getBody().getMessage());
    }

    @Test
    void releaseSeats_whenSuccessful_returnsOkResponse() {
        String idempotencyKey = "idem-release";
        ReleaseSeatsRequest request = releaseRequest();
        ReleaseSeatsResult result = new ReleaseSeatsResult();
        result.setEventId(UUID.fromString(request.getEventId()));
        result.setBookingId(UUID.fromString(request.getBookingId()));
        result.setSeatIds(request.getSeatIds().stream().map(UUID::fromString).toList());
        result.setReleasedCount(request.getSeatIds().size());
        when(internalSeatsService.releaseSeats(
                idempotencyKey,
                request.getEventId(),
                request.getBookingId(),
                request.getSeatIds(),
                request.getReason()
        )).thenReturn(result);

        ResponseEntity<ReleaseSeatsResponse> response = internalSeatsController.releaseSeats(idempotencyKey, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.SUCCESS, response.getBody().getStatus());
        assertEquals("Released " + request.getSeatIds().size() + " seat(s)", response.getBody().getMessage());
        assertEquals(result, response.getBody().getResult());
    }

    @Test
    void releaseLocks_whenSuccessful_returnsReleasedCount() {
        UUID eventId = UUID.randomUUID();
        ReleaseLocksRequest request = releaseLocksRequest();
        when(internalSeatsService.releaseLocks(eventId, request.getUserId(), request.getSeatIds())).thenReturn(2);

        ResponseEntity<ApiResponse> response = internalSeatsController.releaseLocks(eventId, request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.SUCCESS, response.getBody().getStatus());
        assertEquals("Released 2 seat lock(s)", response.getBody().getMessage());
    }

    @Test
    void releaseLocks_whenSeatsDoNotExist_returnsNotFound() {
        UUID eventId = UUID.randomUUID();
        ReleaseLocksRequest request = releaseLocksRequest();
        when(internalSeatsService.releaseLocks(eventId, request.getUserId(), request.getSeatIds()))
                .thenThrow(new SeatsNotFoundException("missing seats"));

        ResponseEntity<ApiResponse> response = internalSeatsController.releaseLocks(eventId, request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("missing seats", response.getBody().getMessage());
    }

    @Test
    void releaseSeats_whenConflict_returnsConflict() {
        String idempotencyKey = "idem-release";
        ReleaseSeatsRequest request = releaseRequest();
        when(internalSeatsService.releaseSeats(
                idempotencyKey,
                request.getEventId(),
                request.getBookingId(),
                request.getSeatIds(),
                request.getReason()
        )).thenThrow(new SeatLockConflictException("seat not releasable"));

        ResponseEntity<ReleaseSeatsResponse> response = internalSeatsController.releaseSeats(idempotencyKey, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("seat not releasable", response.getBody().getMessage());
        verify(internalSeatsService).releaseSeats(
                idempotencyKey,
                request.getEventId(),
                request.getBookingId(),
                request.getSeatIds(),
                request.getReason()
        );
    }

    @Test
    void confirmSeats_whenIdempotencyKeyConflicts_returnsConflict() {
        String idempotencyKey = "idem-confirm";
        SeatConfirmRequest request = confirmRequest();
        when(internalSeatsService.confirmSeats(
                idempotencyKey,
                request.getEventId(),
                request.getBookingId(),
                request.getPaymentId(),
                request.getSeatIds(),
                request.getConfirmedAt()
        )).thenThrow(new IdempotencyConflictException("idempotency key conflict"));

        ResponseEntity<SeatConfirmResponse> response = internalSeatsController.confirmSeats(idempotencyKey, request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("idempotency key conflict", response.getBody().getMessage());
    }

    private SeatConfirmRequest confirmRequest() {
        SeatConfirmRequest request = new SeatConfirmRequest();
        request.setEventId(UUID.randomUUID());
        request.setBookingId(UUID.randomUUID());
        request.setPaymentId(UUID.randomUUID());
        request.setSeatIds(List.of(UUID.randomUUID(), UUID.randomUUID()));
        request.setConfirmedAt(Instant.parse("2026-03-21T12:00:00Z"));
        return request;
    }

    private LockSeatsRequest lockRequest() {
        LockSeatsRequest request = new LockSeatsRequest();
        request.setUserId(UUID.randomUUID());
        request.setIdempotencyKey("idem-key");
        request.setSeatIds(List.of(UUID.randomUUID(), UUID.randomUUID()));
        return request;
    }

    private ReleaseLocksRequest releaseLocksRequest() {
        ReleaseLocksRequest request = new ReleaseLocksRequest();
        request.setUserId(UUID.randomUUID());
        request.setSeatIds(List.of(UUID.randomUUID(), UUID.randomUUID()));
        return request;
    }

    private ReleaseSeatsRequest releaseRequest() {
        ReleaseSeatsRequest request = new ReleaseSeatsRequest();
        request.setEventId(UUID.randomUUID().toString());
        request.setBookingId(UUID.randomUUID().toString());
        request.setSeatIds(List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        request.setReason(ReleaseReason.BOOKING_CANCELLED);
        return request;
    }
}
