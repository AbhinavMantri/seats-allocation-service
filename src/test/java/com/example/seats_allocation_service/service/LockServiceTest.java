package com.example.seats_allocation_service.service;

import com.example.seats_allocation_service.dtos.LockDetail;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.models.EventSeat;
import com.example.seats_allocation_service.repository.EventSeatRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LockServiceTest {

    @Mock
    private EventSeatRepository eventSeatRepository;

    @InjectMocks
    private LockService lockService;

    @Test
    void getLockDetails_whenActiveLocksExist_returnsLockDetail() {
        UUID bookingId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID firstSeatId = UUID.randomUUID();
        UUID secondSeatId = UUID.randomUUID();
        Instant lockExpiresAt = Instant.now().plusSeconds(300);

        EventSeat activeSeatOne = lockedSeat(bookingId, eventId, firstSeatId, lockExpiresAt);
        EventSeat activeSeatTwo = lockedSeat(bookingId, eventId, secondSeatId, lockExpiresAt);
        EventSeat expiredSeat = lockedSeat(bookingId, eventId, UUID.randomUUID(), Instant.now().minusSeconds(60));
        when(eventSeatRepository.findByLockedByAndStatus(bookingId, EventSeat.SeatStatus.LOCKED))
                .thenReturn(List.of(activeSeatTwo, expiredSeat, activeSeatOne));

        LockDetail result = lockService.getLockDetails(bookingId);

        assertEquals(bookingId, result.getBookingId());
        assertEquals(eventId, result.getEventId());
        assertEquals(
                List.of(firstSeatId, secondSeatId).stream().sorted(Comparator.naturalOrder()).toList(),
                result.getSeatIds()
        );
        assertEquals(lockExpiresAt.toString(), result.getLockExpiresAt());
        assertEquals("LOCKED", result.getStatus());
    }

    @Test
    void getLockDetails_whenNoActiveLocksExist_throwsSeatsNotFound() {
        UUID bookingId = UUID.randomUUID();
        EventSeat expiredSeat = lockedSeat(
                bookingId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now().minusSeconds(60)
        );
        when(eventSeatRepository.findByLockedByAndStatus(bookingId, EventSeat.SeatStatus.LOCKED))
                .thenReturn(List.of(expiredSeat));

        assertThrows(SeatsNotFoundException.class, () -> lockService.getLockDetails(bookingId));
    }

    private EventSeat lockedSeat(UUID bookingId, UUID eventId, UUID seatId, Instant lockExpiresAt) {
        EventSeat seat = new EventSeat();
        seat.setId(seatId);
        seat.setEventId(eventId);
        seat.setVenueSeatId(UUID.randomUUID());
        seat.setSectionId(UUID.randomUUID());
        seat.setPriceCents(2000);
        seat.setStatus(EventSeat.SeatStatus.LOCKED);
        seat.setLockedBy(bookingId);
        seat.setLockExpiresAt(lockExpiresAt);
        return seat;
    }
}
