package com.example.seats_allocation_service.service;

import com.example.seats_allocation_service.dtos.ReleaseReason;
import com.example.seats_allocation_service.dtos.ReleaseSeatsResult;
import com.example.seats_allocation_service.dtos.SeatsConfirmation;
import com.example.seats_allocation_service.exceptions.EventNotFoundException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
import com.example.seats_allocation_service.models.EventSeat;
import com.example.seats_allocation_service.repository.EventInventoryContextRepository;
import com.example.seats_allocation_service.repository.EventSeatRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalSeatsServiceTest {

    @Mock
    private EventSeatRepository eventSeatRepository;

    @Mock
    private EventInventoryContextRepository eventInventoryContextRepository;

    @InjectMocks
    private InternalSeatsService internalSeatsService;

    @Test
    void confirmSeats_whenLocksAreActive_booksSeatsAndReturnsConfirmation() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Instant confirmedAt = Instant.parse("2026-03-21T12:00:00Z");
        EventSeat seat = lockedSeat(eventId, bookingId, seatId);
        when(eventSeatRepository.findForUpdateByEventIdAndIds(eventId, List.of(seatId))).thenReturn(List.of(seat));

        SeatsConfirmation result = internalSeatsService.confirmSeats(eventId, bookingId, List.of(seatId), confirmedAt);

        assertEquals(eventId, result.getEventId());
        assertEquals(bookingId, result.getBookingId());
        assertEquals(List.of(seatId), result.getSeatIds());
        assertEquals(1, result.getBookedCount());
        assertEquals(EventSeat.SeatStatus.BOOKED, seat.getStatus());
        assertEquals(bookingId, seat.getBookingId());
        assertNull(seat.getLockedBy());
        assertNull(seat.getLockExpiresAt());
        assertEquals(confirmedAt, seat.getBookedAt());
        verify(eventSeatRepository).saveAll(List.of(seat));
    }

    @Test
    void confirmSeats_whenEventMissing_throwsEventNotFound() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(eventSeatRepository.findForUpdateByEventIdAndIds(eventId, List.of(seatId))).thenReturn(List.of());
        when(eventInventoryContextRepository.existsById(eventId)).thenReturn(false);

        assertThrows(
                EventNotFoundException.class,
                () -> internalSeatsService.confirmSeats(eventId, bookingId, List.of(seatId), Instant.now())
        );
    }

    @Test
    void confirmSeats_whenSeatIsNotActivelyLocked_throwsConflict() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        EventSeat seat = lockedSeat(eventId, bookingId, seatId);
        seat.setLockExpiresAt(Instant.now().minusSeconds(30));
        when(eventSeatRepository.findForUpdateByEventIdAndIds(eventId, List.of(seatId))).thenReturn(List.of(seat));

        assertThrows(
                SeatLockConflictException.class,
                () -> internalSeatsService.confirmSeats(eventId, bookingId, List.of(seatId), Instant.now())
        );
    }

    @Test
    void releaseSeats_whenSeatIsLockedForBooking_releasesAndReturnsResult() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        EventSeat seat = lockedSeat(eventId, bookingId, seatId);
        when(eventSeatRepository.findForUpdateByEventIdAndIds(eventId, List.of(seatId))).thenReturn(List.of(seat));

        ReleaseSeatsResult result = internalSeatsService.releaseSeats(
                eventId.toString(),
                bookingId.toString(),
                List.of(seatId.toString()),
                ReleaseReason.BOOKING_CANCELLED
        );

        assertEquals(eventId, result.getEventId());
        assertEquals(bookingId, result.getBookingId());
        assertEquals(List.of(seatId), result.getSeatIds());
        assertEquals(1, result.getReleasedCount());
        assertEquals(EventSeat.SeatStatus.AVAILABLE, seat.getStatus());
        assertNull(seat.getLockedBy());
        assertNull(seat.getLockExpiresAt());
        assertNull(seat.getBookingId());
        assertNull(seat.getBookedAt());
        verify(eventSeatRepository).saveAll(List.of(seat));
    }

    @Test
    void releaseSeats_whenEventMissing_throwsEventNotFound() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(eventSeatRepository.findForUpdateByEventIdAndIds(eventId, List.of(seatId))).thenReturn(List.of());
        when(eventInventoryContextRepository.existsById(eventId)).thenReturn(false);

        assertThrows(
                EventNotFoundException.class,
                () -> internalSeatsService.releaseSeats(
                        eventId.toString(),
                        bookingId.toString(),
                        List.of(seatId.toString()),
                        ReleaseReason.BOOKING_CANCELLED
                )
        );
    }

    @Test
    void releaseSeats_whenSeatIsNotReleasable_throwsConflict() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        EventSeat seat = new EventSeat();
        seat.setId(seatId);
        seat.setEventId(eventId);
        seat.setVenueSeatId(UUID.randomUUID());
        seat.setSectionId(UUID.randomUUID());
        seat.setPriceCents(1500);
        seat.setStatus(EventSeat.SeatStatus.AVAILABLE);
        when(eventSeatRepository.findForUpdateByEventIdAndIds(eventId, List.of(seatId))).thenReturn(List.of(seat));

        assertThrows(
                SeatLockConflictException.class,
                () -> internalSeatsService.releaseSeats(
                        eventId.toString(),
                        bookingId.toString(),
                        List.of(seatId.toString()),
                        ReleaseReason.BOOKING_CANCELLED
                )
        );
    }

    private EventSeat lockedSeat(UUID eventId, UUID bookingId, UUID seatId) {
        EventSeat seat = new EventSeat();
        seat.setId(seatId);
        seat.setEventId(eventId);
        seat.setVenueSeatId(UUID.randomUUID());
        seat.setSectionId(UUID.randomUUID());
        seat.setPriceCents(1500);
        seat.setStatus(EventSeat.SeatStatus.LOCKED);
        seat.setLockedBy(bookingId);
        seat.setLockExpiresAt(Instant.now().plusSeconds(300));
        return seat;
    }
}
