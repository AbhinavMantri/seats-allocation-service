package com.example.seats_allocation_service.service;

import com.example.seats_allocation_service.dtos.ReleaseReason;
import com.example.seats_allocation_service.dtos.ReleaseSeatsResult;
import com.example.seats_allocation_service.dtos.SeatsConfirmation;
import com.example.seats_allocation_service.exceptions.EventNotFoundException;
import com.example.seats_allocation_service.exceptions.IdempotencyConflictException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
import com.example.seats_allocation_service.models.AllocationIdempotency;
import com.example.seats_allocation_service.repository.AllocationIdempotencyRepository;
import com.example.seats_allocation_service.models.EventSeat;
import com.example.seats_allocation_service.repository.EventInventoryContextRepository;
import com.example.seats_allocation_service.repository.EventSeatRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InternalSeatsServiceTest {

    @Mock
    private EventSeatRepository eventSeatRepository;

    @Mock
    private EventSeatService eventSeatService;

    @Mock
    private EventInventoryContextRepository eventInventoryContextRepository;

    @Mock
    private AllocationIdempotencyRepository allocationIdempotencyRepository;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private InternalSeatsService internalSeatsService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        internalSeatsService = new InternalSeatsService(
                eventSeatService,
                eventSeatRepository,
                eventInventoryContextRepository,
                allocationIdempotencyRepository,
                stringRedisTemplate,
                objectMapper
        );
        lenient().when(allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey(anyString(), any(UUID.class), anyString()))
                .thenReturn(java.util.Optional.empty());
        lenient().when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void confirmSeats_whenLocksAreActive_booksSeatsAndReturnsConfirmation() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Instant confirmedAt = Instant.parse("2026-03-21T12:00:00Z");
        EventSeat seat = lockedSeat(eventId, bookingId, seatId);
        when(eventSeatRepository.findForUpdateByEventIdAndIds(eventId, List.of(seatId))).thenReturn(List.of(seat));

        SeatsConfirmation result = internalSeatsService.confirmSeats("idem-confirm", eventId, bookingId, UUID.randomUUID(), List.of(seatId), confirmedAt);

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
                () -> internalSeatsService.confirmSeats("idem-confirm", eventId, bookingId, UUID.randomUUID(), List.of(seatId), Instant.now())
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
                () -> internalSeatsService.confirmSeats("idem-confirm", eventId, bookingId, UUID.randomUUID(), List.of(seatId), Instant.now())
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
                "idem-release",
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
                        "idem-release",
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
                        "idem-release",
                        eventId.toString(),
                        bookingId.toString(),
                        List.of(seatId.toString()),
                        ReleaseReason.BOOKING_CANCELLED
                )
        );
    }

    @Test
    void confirmSeats_whenIdempotencyRecordExists_replaysStoredResponse() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Instant confirmedAt = Instant.parse("2026-03-21T12:00:00Z");

        SeatsConfirmation stored = SeatsConfirmation.builder()
                .eventId(eventId)
                .bookingId(bookingId)
                .paymentId(paymentId)
                .seatIds(List.of(seatId))
                .bookedCount(1)
                .confirmedAt(confirmedAt.toString())
                .build();
        AllocationIdempotency idempotency = new AllocationIdempotency();
        idempotency.setOperationType("SEAT_CONFIRM");
        idempotency.setResourceId(eventId);
        idempotency.setIdempotencyKey("idem-confirm");
        idempotency.setPayloadHash(hash(eventId, bookingId, paymentId, List.of(seatId), confirmedAt));
        idempotency.setResponsePayload(objectMapper.writeValueAsString(stored));
        when(allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey("SEAT_CONFIRM", eventId, "idem-confirm"))
                .thenReturn(java.util.Optional.of(idempotency));

        SeatsConfirmation result = internalSeatsService.confirmSeats("idem-confirm", eventId, bookingId, paymentId, List.of(seatId), confirmedAt);

        assertEquals(stored, result);
        verify(eventSeatRepository, never()).findForUpdateByEventIdAndIds(any(UUID.class), any());
    }

    @Test
    void confirmSeats_whenRedisReplayExists_replaysStoredResponse() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Instant confirmedAt = Instant.parse("2026-03-21T12:00:00Z");

        SeatsConfirmation stored = SeatsConfirmation.builder()
                .eventId(eventId)
                .bookingId(bookingId)
                .paymentId(paymentId)
                .seatIds(List.of(seatId))
                .bookedCount(1)
                .confirmedAt(confirmedAt.toString())
                .build();
        String cachedPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "payloadHash", hash(eventId, bookingId, paymentId, List.of(seatId), confirmedAt),
                "responsePayload", objectMapper.writeValueAsString(stored)
        ));
        when(valueOperations.get("internal:seats:confirm:response:" + eventId + ":idem-confirm")).thenReturn(cachedPayload);

        SeatsConfirmation result = internalSeatsService.confirmSeats("idem-confirm", eventId, bookingId, paymentId, List.of(seatId), confirmedAt);

        assertEquals(stored, result);
        verify(allocationIdempotencyRepository, never()).findByOperationTypeAndResourceIdAndIdempotencyKey(anyString(), any(UUID.class), anyString());
        verify(eventSeatRepository, never()).findForUpdateByEventIdAndIds(any(UUID.class), any());
    }

    @Test
    void confirmSeats_whenIdempotencyKeyIsReusedWithDifferentPayload_throwsConflict() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Instant confirmedAt = Instant.parse("2026-03-21T12:00:00Z");

        AllocationIdempotency idempotency = new AllocationIdempotency();
        idempotency.setOperationType("SEAT_CONFIRM");
        idempotency.setResourceId(eventId);
        idempotency.setIdempotencyKey("idem-confirm");
        idempotency.setPayloadHash("different-hash");
        idempotency.setResponsePayload("{}");
        when(allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey("SEAT_CONFIRM", eventId, "idem-confirm"))
                .thenReturn(java.util.Optional.of(idempotency));

        assertThrows(
                IdempotencyConflictException.class,
                () -> internalSeatsService.confirmSeats("idem-confirm", eventId, bookingId, UUID.randomUUID(), List.of(seatId), confirmedAt)
        );
        verify(eventSeatRepository, never()).findForUpdateByEventIdAndIds(any(UUID.class), any());
    }

    @Test
    void releaseSeats_whenIdempotencyRecordExists_replaysStoredResponse() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        ReleaseSeatsResult stored = new ReleaseSeatsResult();
        stored.setEventId(eventId);
        stored.setBookingId(bookingId);
        stored.setSeatIds(List.of(seatId));
        stored.setReleasedCount(1);

        AllocationIdempotency idempotency = new AllocationIdempotency();
        idempotency.setOperationType("SEAT_RELEASE");
        idempotency.setResourceId(eventId);
        idempotency.setIdempotencyKey("idem-release");
        idempotency.setPayloadHash(hash(eventId, bookingId, List.of(seatId), ReleaseReason.BOOKING_CANCELLED));
        idempotency.setResponsePayload(objectMapper.writeValueAsString(stored));
        when(allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey("SEAT_RELEASE", eventId, "idem-release"))
                .thenReturn(java.util.Optional.of(idempotency));

        ReleaseSeatsResult result = internalSeatsService.releaseSeats(
                "idem-release",
                eventId.toString(),
                bookingId.toString(),
                List.of(seatId.toString()),
                ReleaseReason.BOOKING_CANCELLED
        );

        assertEquals(stored.getEventId(), result.getEventId());
        assertEquals(stored.getBookingId(), result.getBookingId());
        assertEquals(stored.getSeatIds(), result.getSeatIds());
        assertEquals(stored.getReleasedCount(), result.getReleasedCount());
        verify(eventSeatRepository, never()).findForUpdateByEventIdAndIds(any(UUID.class), any());
    }

    @Test
    void releaseSeats_whenRedisReplayExists_replaysStoredResponse() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        ReleaseSeatsResult stored = new ReleaseSeatsResult();
        stored.setEventId(eventId);
        stored.setBookingId(bookingId);
        stored.setSeatIds(List.of(seatId));
        stored.setReleasedCount(1);
        String cachedPayload = objectMapper.writeValueAsString(java.util.Map.of(
                "payloadHash", hash(eventId, bookingId, List.of(seatId), ReleaseReason.BOOKING_CANCELLED),
                "responsePayload", objectMapper.writeValueAsString(stored)
        ));
        when(valueOperations.get("internal:seats:release:response:" + eventId + ":idem-release")).thenReturn(cachedPayload);

        ReleaseSeatsResult result = internalSeatsService.releaseSeats(
                "idem-release",
                eventId.toString(),
                bookingId.toString(),
                List.of(seatId.toString()),
                ReleaseReason.BOOKING_CANCELLED
        );

        assertEquals(stored.getEventId(), result.getEventId());
        assertEquals(stored.getBookingId(), result.getBookingId());
        assertEquals(stored.getSeatIds(), result.getSeatIds());
        assertEquals(stored.getReleasedCount(), result.getReleasedCount());
        verify(allocationIdempotencyRepository, never()).findByOperationTypeAndResourceIdAndIdempotencyKey(anyString(), any(UUID.class), anyString());
        verify(eventSeatRepository, never()).findForUpdateByEventIdAndIds(any(UUID.class), any());
    }

    @Test
    void releaseSeats_whenIdempotencyKeyIsReusedWithDifferentPayload_throwsConflict() {
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();

        AllocationIdempotency idempotency = new AllocationIdempotency();
        idempotency.setOperationType("SEAT_RELEASE");
        idempotency.setResourceId(eventId);
        idempotency.setIdempotencyKey("idem-release");
        idempotency.setPayloadHash("different-hash");
        idempotency.setResponsePayload("{}");
        when(allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey("SEAT_RELEASE", eventId, "idem-release"))
                .thenReturn(java.util.Optional.of(idempotency));

        assertThrows(
                IdempotencyConflictException.class,
                () -> internalSeatsService.releaseSeats(
                        "idem-release",
                        eventId.toString(),
                        bookingId.toString(),
                        List.of(seatId.toString()),
                        ReleaseReason.BOOKING_CANCELLED
                )
        );
        verify(eventSeatRepository, never()).findForUpdateByEventIdAndIds(any(UUID.class), any());
    }

    private String hash(Object... values) {
        StringBuilder canonical = new StringBuilder();
        for (Object value : values) {
            if (canonical.length() > 0) {
                canonical.append('|');
            }
            canonical.append(value == null ? "null" : value.toString());
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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
