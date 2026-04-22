package com.example.seats_allocation_service.service;

import com.example.seats_allocation_service.exceptions.EventNotFoundException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.models.AllocationIdempotency;
import com.example.seats_allocation_service.models.EventSeat;
import com.example.seats_allocation_service.repository.AllocationIdempotencyRepository;
import com.example.seats_allocation_service.repository.EventInventoryContextRepository;
import com.example.seats_allocation_service.repository.EventSeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventSeatServiceTest {

    @Mock
    private EventSeatRepository eventSeatRepository;

    @Mock
    private EventInventoryContextRepository eventInventoryContextRepository;

    @Mock
    private AllocationIdempotencyRepository allocationIdempotencyRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private EventSeatService eventSeatService;

    @BeforeEach
    void setUp() {
        eventSeatService = new EventSeatService(
                eventSeatRepository,
                eventInventoryContextRepository,
                allocationIdempotencyRepository,
                stringRedisTemplate,
                new ObjectMapper()
        );
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void getSeats_whenCacheHit_returnsCachedSeats() throws Exception {
        UUID eventId = UUID.randomUUID();
        EventSeat seat = seat(eventId, UUID.randomUUID(), EventSeat.SeatStatus.AVAILABLE);
        String payload = new ObjectMapper().writeValueAsString(List.of(seat));
        when(valueOperations.get("event:seats:" + eventId)).thenReturn(payload);

        List<EventSeat> result = eventSeatService.getSeats(eventId);

        assertEquals(1, result.size());
        assertEquals(seat.getId(), result.getFirst().getId());
        verify(eventSeatRepository, never()).findByEventId(eventId);
    }

    @Test
    void getSeats_whenEventIsMissing_throwsEventNotFound() {
        UUID eventId = UUID.randomUUID();
        when(valueOperations.get("event:seats:" + eventId)).thenReturn(null);
        when(eventSeatRepository.findByEventId(eventId)).thenReturn(List.of());
        when(eventInventoryContextRepository.existsById(eventId)).thenReturn(false);

        assertThrows(EventNotFoundException.class, () -> eventSeatService.getSeats(eventId));
    }

    @Test
    void getAvailabilitySummary_whenSeatsExist_returnsCounts() {
        UUID eventId = UUID.randomUUID();
        EventSeat availableSeat = seat(eventId, UUID.randomUUID(), EventSeat.SeatStatus.AVAILABLE);
        EventSeat activeLockedSeat = seat(eventId, UUID.randomUUID(), EventSeat.SeatStatus.LOCKED);
        activeLockedSeat.setLockExpiresAt(Instant.now().plusSeconds(300));
        EventSeat expiredLockedSeat = seat(eventId, UUID.randomUUID(), EventSeat.SeatStatus.LOCKED);
        expiredLockedSeat.setLockExpiresAt(Instant.now().minusSeconds(60));
        EventSeat bookedSeat = seat(eventId, UUID.randomUUID(), EventSeat.SeatStatus.BOOKED);
        when(eventSeatRepository.findByEventId(eventId))
                .thenReturn(List.of(availableSeat, activeLockedSeat, expiredLockedSeat, bookedSeat));

        var result = eventSeatService.getAvailabilitySummary(eventId);

        assertEquals(4, result.getTotalSeats());
        assertEquals(2, result.getAvailableSeats());
        assertEquals(1, result.getLockedSeats());
    }

    @Test
    void getAvailabilitySummary_whenEventIsMissing_throwsEventNotFound() {
        UUID eventId = UUID.randomUUID();
        when(eventSeatRepository.findByEventId(eventId)).thenReturn(List.of());
        when(eventInventoryContextRepository.existsById(eventId)).thenReturn(false);

        assertThrows(EventNotFoundException.class, () -> eventSeatService.getAvailabilitySummary(eventId));
    }

    @Test
    void lockSeats_whenResponseIsCached_returnsWithoutDbWrites() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        when(valueOperations.get("event:locks:response:" + eventId + ":" + userId + ":idem"))
                .thenReturn("{\"status\":\"SUCCESS\",\"message\":\"cached\"}");

        assertDoesNotThrow(() -> eventSeatService.lockSeats(eventId, "idem", userId, List.of(UUID.randomUUID())));

        verify(allocationIdempotencyRepository, never()).findByOperationTypeAndResourceIdAndIdempotencyKey(anyString(), any(), anyString());
        verify(eventSeatRepository, never()).findForUpdateByEventIdAndIds(any(), any());
    }

    @Test
    void lockSeats_whenExistingIdempotencyHasDifferentSeatHash_throwsConflict() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(valueOperations.get(anyString())).thenReturn(null);
        AllocationIdempotency idempotency = new AllocationIdempotency();
        idempotency.setPayloadHash("different-hash");
        when(allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey("SEAT_LOCK", eventId, userId + ":idem"))
                .thenReturn(Optional.of(idempotency));

        assertThrows(
                SeatLockConflictException.class,
                () -> eventSeatService.lockSeats(eventId, "idem", userId, List.of(seatId))
        );
    }

    @Test
    void lockSeats_whenSeatIsLockedByAnotherUser_throwsConflict() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(valueOperations.get(anyString())).thenReturn(null);
        when(allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey("SEAT_LOCK", eventId, userId + ":idem"))
                .thenReturn(Optional.empty());
        EventSeat seat = seat(eventId, seatId, EventSeat.SeatStatus.LOCKED);
        seat.setLockedBy(otherUserId);
        seat.setLockExpiresAt(Instant.now().plusSeconds(300));
        when(eventSeatRepository.findForUpdateByEventIdAndIds(eventId, List.of(seatId))).thenReturn(List.of(seat));

        assertThrows(
                SeatLockConflictException.class,
                () -> eventSeatService.lockSeats(eventId, "idem", userId, List.of(seatId))
        );
    }

    @Test
    void lockSeats_whenSuccessful_locksSeatsAndPersistsIdempotency() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(valueOperations.get(anyString())).thenReturn(null);
        when(allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey("SEAT_LOCK", eventId, userId + ":idem"))
                .thenReturn(Optional.empty());
        EventSeat seat = seat(eventId, seatId, EventSeat.SeatStatus.AVAILABLE);
        when(eventSeatRepository.findForUpdateByEventIdAndIds(eventId, List.of(seatId))).thenReturn(List.of(seat));
        doNothing().when(valueOperations).set(anyString(), anyString(), any(Duration.class));

        eventSeatService.lockSeats(eventId, "idem", userId, List.of(seatId));

        assertEquals(EventSeat.SeatStatus.LOCKED, seat.getStatus());
        assertEquals(userId, seat.getLockedBy());
        assertNotNull(seat.getLockExpiresAt());
        verify(eventSeatRepository).saveAll(List.of(seat));

        ArgumentCaptor<String> responseCaptor = ArgumentCaptor.forClass(String.class);
        verify(allocationIdempotencyRepository).insertRecord(
                eq("SEAT_LOCK"),
                eq(eventId),
                eq(userId + ":idem"),
                anyString(),
                responseCaptor.capture()
        );
        assertNotNull(responseCaptor.getValue());
    }

    @Test
    void releaseLocks_whenEventDoesNotExist_throwsEventNotFound() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(eventSeatRepository.findForUpdateByEventIdAndIds(eventId, List.of(seatId))).thenReturn(List.of());
        when(eventInventoryContextRepository.existsById(eventId)).thenReturn(false);

        assertThrows(
                EventNotFoundException.class,
                () -> eventSeatService.releaseLocks(eventId, userId, List.of(seatId))
        );
    }

    @Test
    void releaseLocks_whenLocksBelongToUser_releasesAndReturnsCount() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        EventSeat seat = seat(eventId, seatId, EventSeat.SeatStatus.LOCKED);
        seat.setLockedBy(userId);
        seat.setLockExpiresAt(Instant.now().plusSeconds(300));
        when(eventSeatRepository.findForUpdateByEventIdAndIds(eventId, List.of(seatId))).thenReturn(List.of(seat));

        int released = eventSeatService.releaseLocks(eventId, userId, List.of(seatId));

        assertEquals(1, released);
        assertEquals(EventSeat.SeatStatus.AVAILABLE, seat.getStatus());
        assertEquals(null, seat.getLockedBy());
        assertEquals(null, seat.getLockExpiresAt());
        verify(eventSeatRepository).saveAll(List.of(seat));
    }

    @Test
    void releaseLocks_whenSomeSeatsAreMissing_throwsSeatsNotFound() {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        UUID missingSeatId = UUID.randomUUID();
        EventSeat seat = seat(eventId, seatId, EventSeat.SeatStatus.LOCKED);
        when(eventSeatRepository.findForUpdateByEventIdAndIds(eventId, List.of(seatId, missingSeatId))).thenReturn(List.of(seat));
        when(eventInventoryContextRepository.existsById(eventId)).thenReturn(true);

        assertThrows(
                SeatsNotFoundException.class,
                () -> eventSeatService.releaseLocks(eventId, userId, List.of(seatId, missingSeatId))
        );
    }

    private EventSeat seat(UUID eventId, UUID seatId, EventSeat.SeatStatus status) {
        EventSeat seat = new EventSeat();
        seat.setId(seatId);
        seat.setEventId(eventId);
        seat.setVenueSeatId(UUID.randomUUID());
        seat.setSectionId(UUID.randomUUID());
        seat.setPriceCents(1800);
        seat.setStatus(status);
        return seat;
    }
}
