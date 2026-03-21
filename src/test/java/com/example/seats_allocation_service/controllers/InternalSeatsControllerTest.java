package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.dtos.ReleaseReason;
import com.example.seats_allocation_service.dtos.ReleaseSeatsRequest;
import com.example.seats_allocation_service.dtos.ReleaseSeatsResponse;
import com.example.seats_allocation_service.dtos.ReleaseSeatsResult;
import com.example.seats_allocation_service.dtos.SeatConfirmRequest;
import com.example.seats_allocation_service.dtos.SeatConfirmResponse;
import com.example.seats_allocation_service.dtos.SeatsConfirmation;
import com.example.seats_allocation_service.dtos.common.ResponseStatus;
import com.example.seats_allocation_service.exceptions.EventNotFoundException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalSeatsControllerTest {

    @Mock
    private InternalSeatsService internalSeatsService;

    @InjectMocks
    private InternalSeatsController internalSeatsController;

    @Test
    void confirmSeats_whenSuccessful_returnsOkResponse() {
        SeatConfirmRequest request = confirmRequest();
        SeatsConfirmation confirmation = SeatsConfirmation.builder()
                .eventId(request.getEventId())
                .bookingId(request.getBookingId())
                .seatIds(request.getSeatIds())
                .bookedCount(request.getSeatIds().size())
                .confirmedAt(request.getConfirmedAt().toString())
                .build();
        when(internalSeatsService.confirmSeats(
                request.getEventId(),
                request.getBookingId(),
                request.getSeatIds(),
                request.getConfirmedAt()
        )).thenReturn(confirmation);

        ResponseEntity<SeatConfirmResponse> response = internalSeatsController.confirmSeats(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.SUCCESS, response.getBody().getStatus());
        assertEquals("Confirmed " + request.getSeatIds().size() + " seat(s)", response.getBody().getMessage());
        assertEquals(confirmation, response.getBody().getSeatConfirmation());
    }

    @Test
    void confirmSeats_whenEventMissing_returnsNotFound() {
        SeatConfirmRequest request = confirmRequest();
        when(internalSeatsService.confirmSeats(
                request.getEventId(),
                request.getBookingId(),
                request.getSeatIds(),
                request.getConfirmedAt()
        )).thenThrow(new EventNotFoundException("missing event"));

        ResponseEntity<SeatConfirmResponse> response = internalSeatsController.confirmSeats(request);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("missing event", response.getBody().getMessage());
    }

    @Test
    void releaseSeats_whenSuccessful_returnsOkResponse() {
        ReleaseSeatsRequest request = releaseRequest();
        ReleaseSeatsResult result = new ReleaseSeatsResult();
        result.setEventId(UUID.fromString(request.getEventId()));
        result.setBookingId(UUID.fromString(request.getBookingId()));
        result.setSeatIds(request.getSeatIds().stream().map(UUID::fromString).toList());
        result.setReleasedCount(request.getSeatIds().size());
        when(internalSeatsService.releaseSeats(
                request.getEventId(),
                request.getBookingId(),
                request.getSeatIds(),
                request.getReason()
        )).thenReturn(result);

        ResponseEntity<ReleaseSeatsResponse> response = internalSeatsController.releaseSeats(request);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.SUCCESS, response.getBody().getStatus());
        assertEquals("Released " + request.getSeatIds().size() + " seat(s)", response.getBody().getMessage());
        assertEquals(result, response.getBody().getResult());
    }

    @Test
    void releaseSeats_whenConflict_returnsConflict() {
        ReleaseSeatsRequest request = releaseRequest();
        when(internalSeatsService.releaseSeats(
                request.getEventId(),
                request.getBookingId(),
                request.getSeatIds(),
                request.getReason()
        )).thenThrow(new SeatLockConflictException("seat not releasable"));

        ResponseEntity<ReleaseSeatsResponse> response = internalSeatsController.releaseSeats(request);

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(ResponseStatus.FAILURE, response.getBody().getStatus());
        assertEquals("seat not releasable", response.getBody().getMessage());
        verify(internalSeatsService).releaseSeats(
                request.getEventId(),
                request.getBookingId(),
                request.getSeatIds(),
                request.getReason()
        );
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

    private ReleaseSeatsRequest releaseRequest() {
        ReleaseSeatsRequest request = new ReleaseSeatsRequest();
        request.setEventId(UUID.randomUUID().toString());
        request.setBookingId(UUID.randomUUID().toString());
        request.setSeatIds(List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        request.setReason(ReleaseReason.BOOKING_CANCELLED);
        return request;
    }
}
