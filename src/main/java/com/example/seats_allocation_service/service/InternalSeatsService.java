package com.example.seats_allocation_service.service;

import com.example.seats_allocation_service.dtos.ReleaseReason;
import com.example.seats_allocation_service.dtos.ReleaseSeatsResult;
import com.example.seats_allocation_service.dtos.SeatsConfirmation;
import com.example.seats_allocation_service.exceptions.IdempotencyConflictException;
import com.example.seats_allocation_service.exceptions.EventNotFoundException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.models.AllocationIdempotency;
import com.example.seats_allocation_service.models.EventSeat;
import com.example.seats_allocation_service.repository.AllocationIdempotencyRepository;
import com.example.seats_allocation_service.repository.EventInventoryContextRepository;
import com.example.seats_allocation_service.repository.EventSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InternalSeatsService {
    private static final String OPERATION_SEAT_CONFIRM = "SEAT_CONFIRM";
    private static final String OPERATION_SEAT_RELEASE = "SEAT_RELEASE";
    private static final Duration IDEMPOTENCY_RESPONSE_CACHE_TTL = Duration.ofHours(1);
    private static final String CONFIRM_RESPONSE_CACHE_KEY_PREFIX = "internal:seats:confirm:response:";
    private static final String RELEASE_RESPONSE_CACHE_KEY_PREFIX = "internal:seats:release:response:";
    private final EventSeatService eventSeatService;
    private final EventSeatRepository eventSeatRepository;
    private final EventInventoryContextRepository eventInventoryContextRepository;
    private final AllocationIdempotencyRepository allocationIdempotencyRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public void lockSeats(UUID eventId, String idempotencyKey, UUID userId, List<UUID> seatIds) {
        eventSeatService.lockSeats(eventId, idempotencyKey, userId, seatIds);
    }

    @Transactional
    public SeatsConfirmation confirmSeats(String idempotencyKey, UUID eventId, UUID bookingId, UUID paymentId, List<UUID> seatIds, Instant confirmedAt) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", "internal-seats-confirm")) {
            long startTimeNanos = System.nanoTime();
            log.info("confirmSeats service started for eventId={} bookingId={} inputSeatCount={} confirmedAt={} idempotencyKey={}",
                    eventId, bookingId, seatIds == null ? 0 : seatIds.size(), confirmedAt, idempotencyKey);
            String payloadHash = hashPayload(eventId, bookingId, paymentId, seatIds, confirmedAt);
            String cacheKey = cacheKey(CONFIRM_RESPONSE_CACHE_KEY_PREFIX, eventId, idempotencyKey);
            SeatsConfirmation redisReplay = readCachedResponse(cacheKey, payloadHash, SeatsConfirmation.class);
            if (redisReplay != null) {
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("confirmSeats service replayed redis response for eventId={} bookingId={} idempotencyKey={} latencyMs={}",
                        eventId, bookingId, idempotencyKey, latencyMs);
                return redisReplay;
            }
            SeatsConfirmation replay = readIdempotentResponse(OPERATION_SEAT_CONFIRM, eventId, idempotencyKey, payloadHash, SeatsConfirmation.class);
            if (replay != null) {
                cacheResponsePayload(cacheKey, payloadHash, writeResponse(replay));
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("confirmSeats service replayed idempotent response for eventId={} bookingId={} idempotencyKey={} latencyMs={}",
                        eventId, bookingId, idempotencyKey, latencyMs);
                return replay;
            }

            List<EventSeat> seatsToConfirm = eventSeatRepository.findForUpdateByEventIdAndIds(eventId, seatIds);

            if (seatsToConfirm.isEmpty()) {
                if (!eventInventoryContextRepository.existsById(eventId)) {
                    long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                    log.warn("confirmSeats service event not found for eventId={} bookingId={} latencyMs={}",
                            eventId, bookingId, latencyMs);
                    throw new EventNotFoundException("Event not found for eventId: " + eventId);
                }
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("confirmSeats service no requested seats found for eventId={} bookingId={} latencyMs={}",
                        eventId, bookingId, latencyMs);
                throw new SeatsNotFoundException("No requested seats found for eventId: " + eventId);
            }

            if (seatsToConfirm.size() != seatIds.size()) {
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("confirmSeats service some seats missing for eventId={} bookingId={} requestedSeatCount={} foundSeatCount={} latencyMs={}",
                        eventId, bookingId, seatIds.size(), seatsToConfirm.size(), latencyMs);
                throw new SeatsNotFoundException("Some requested seats were not found for eventId: " + eventId);
            }

            Instant now = Instant.now();
            for (EventSeat seat : seatsToConfirm) {
                if (seat.getStatus() != EventSeat.SeatStatus.LOCKED
                        || !bookingId.equals(seat.getLockedBy())
                        || seat.getLockExpiresAt() == null
                        || !seat.getLockExpiresAt().isAfter(now)) {
                    long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                    log.warn("confirmSeats service seat not actively locked for eventId={} bookingId={} seatId={} latencyMs={}",
                            eventId, bookingId, seat.getId(), latencyMs);
                    throw new SeatLockConflictException("Seat " + seat.getId() + " is not actively locked for booking " + bookingId);
                }

                seat.setStatus(EventSeat.SeatStatus.BOOKED);
                seat.setBookingId(bookingId);
                seat.setLockExpiresAt(null);
                seat.setLockedBy(null);
                seat.setBookedAt(confirmedAt);
            }

            eventSeatRepository.saveAll(seatsToConfirm);
            SeatsConfirmation result = SeatsConfirmation.builder()
                    .eventId(eventId)
                    .bookingId(bookingId)
                    .paymentId(paymentId)
                    .seatIds(seatIds)
                    .bookedCount(seatsToConfirm.size())
                    .confirmedAt(confirmedAt.toString())
                    .build();
            cacheIdempotentResponse(OPERATION_SEAT_CONFIRM, eventId, idempotencyKey, payloadHash, cacheKey, result);
            long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
            log.info("confirmSeats service completed for eventId={} bookingId={} bookedCount={} latencyMs={}",
                    eventId, bookingId, result.getBookedCount(), latencyMs);
            return result;
        }
    }

    @Transactional
    public ReleaseSeatsResult releaseSeats(String idempotencyKey, String eventId, String bookingId, List<String> seatIds, ReleaseReason reason) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", "internal-seats-release")) {
            long startTimeNanos = System.nanoTime();
            UUID parsedEventId = UUID.fromString(eventId);
            UUID parsedBookingId = UUID.fromString(bookingId);
            List<UUID> parsedSeatIds = seatIds.stream()
                    .map(UUID::fromString)
                    .toList();
            log.info("releaseSeats service started for eventId={} bookingId={} inputSeatCount={} reason={} idempotencyKey={}",
                    parsedEventId, parsedBookingId, parsedSeatIds.size(), reason, idempotencyKey);
            String payloadHash = hashPayload(parsedEventId, parsedBookingId, parsedSeatIds, reason);
            String cacheKey = cacheKey(RELEASE_RESPONSE_CACHE_KEY_PREFIX, parsedEventId, idempotencyKey);
            ReleaseSeatsResult redisReplay = readCachedResponse(cacheKey, payloadHash, ReleaseSeatsResult.class);
            if (redisReplay != null) {
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("releaseSeats service replayed redis response for eventId={} bookingId={} idempotencyKey={} latencyMs={}",
                        parsedEventId, parsedBookingId, idempotencyKey, latencyMs);
                return redisReplay;
            }
            ReleaseSeatsResult replay = readIdempotentResponse(OPERATION_SEAT_RELEASE, parsedEventId, idempotencyKey, payloadHash, ReleaseSeatsResult.class);
            if (replay != null) {
                cacheResponsePayload(cacheKey, payloadHash, writeResponse(replay));
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("releaseSeats service replayed idempotent response for eventId={} bookingId={} idempotencyKey={} latencyMs={}",
                        parsedEventId, parsedBookingId, idempotencyKey, latencyMs);
                return replay;
            }

            List<EventSeat> seatsToRelease = eventSeatRepository.findForUpdateByEventIdAndIds(parsedEventId, parsedSeatIds);

            if (seatsToRelease.isEmpty()) {
                if (!eventInventoryContextRepository.existsById(parsedEventId)) {
                    long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                    log.warn("releaseSeats service event not found for eventId={} bookingId={} latencyMs={}",
                            parsedEventId, parsedBookingId, latencyMs);
                    throw new EventNotFoundException("Event not found for eventId: " + parsedEventId);
                }
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("releaseSeats service no requested seats found for eventId={} bookingId={} latencyMs={}",
                        parsedEventId, parsedBookingId, latencyMs);
                throw new SeatsNotFoundException("No requested seats found for eventId: " + parsedEventId);
            }

            if (seatsToRelease.size() != parsedSeatIds.size()) {
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("releaseSeats service some seats missing for eventId={} bookingId={} requestedSeatCount={} foundSeatCount={} latencyMs={}",
                        parsedEventId, parsedBookingId, parsedSeatIds.size(), seatsToRelease.size(), latencyMs);
                throw new SeatsNotFoundException("Some requested seats were not found for eventId: " + parsedEventId);
            }

            for (EventSeat seat : seatsToRelease) {
                boolean lockedForBooking = seat.getStatus() == EventSeat.SeatStatus.LOCKED
                        && parsedBookingId.equals(seat.getLockedBy());
                boolean bookedForBooking = seat.getStatus() == EventSeat.SeatStatus.BOOKED
                        && parsedBookingId.equals(seat.getBookingId());

                if (!lockedForBooking && !bookedForBooking) {
                    long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                    log.warn("releaseSeats service seat not releasable for eventId={} bookingId={} seatId={} latencyMs={}",
                            parsedEventId, parsedBookingId, seat.getId(), latencyMs);
                    throw new SeatLockConflictException(
                            "Seat " + seat.getId() + " is not releasable for booking " + parsedBookingId);
                }

                seat.setStatus(EventSeat.SeatStatus.AVAILABLE);
                seat.setLockedBy(null);
                seat.setLockExpiresAt(null);
                seat.setBookingId(null);
                seat.setBookedAt(null);
            }

            eventSeatRepository.saveAll(seatsToRelease);

            ReleaseSeatsResult result = new ReleaseSeatsResult();
            result.setEventId(parsedEventId);
            result.setBookingId(parsedBookingId);
            result.setSeatIds(parsedSeatIds);
            result.setReleasedCount(seatsToRelease.size());
            cacheIdempotentResponse(OPERATION_SEAT_RELEASE, parsedEventId, idempotencyKey, payloadHash, cacheKey, result);
            long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
            log.info("releaseSeats service completed for eventId={} bookingId={} releasedCount={} reason={} latencyMs={}",
                    parsedEventId, parsedBookingId, result.getReleasedCount(), reason, latencyMs);
            return result;
        }
    }

    public int releaseLocks(UUID eventId, UUID userId, List<UUID> seatIds) {
        return eventSeatService.releaseLocks(eventId, userId, seatIds);
    }

    private void cacheIdempotentResponse(String operationType, UUID resourceId, String idempotencyKey, String payloadHash, String cacheKey, Object response) {
        String responsePayload = writeResponse(response);
        AllocationIdempotency idempotency = new AllocationIdempotency();
        idempotency.setOperationType(operationType);
        idempotency.setResourceId(resourceId);
        idempotency.setIdempotencyKey(idempotencyKey);
        idempotency.setPayloadHash(payloadHash);
        idempotency.setResponsePayload(responsePayload);
        try {
            allocationIdempotencyRepository.save(idempotency);
        } catch (DataIntegrityViolationException ex) {
            log.info("Idempotency record already persisted for operationType={} resourceId={} idempotencyKey={}",
                    operationType, resourceId, idempotencyKey);
        }
        cacheResponsePayload(cacheKey, payloadHash, responsePayload);
    }

    private <T> T readIdempotentResponse(String operationType, UUID resourceId, String idempotencyKey, String payloadHash, Class<T> responseType) {
        AllocationIdempotency existing = allocationIdempotencyRepository
                .findByOperationTypeAndResourceIdAndIdempotencyKey(operationType, resourceId, idempotencyKey)
                .orElse(null);
        if (existing == null) {
            return null;
        }
        if (!existing.getPayloadHash().equals(payloadHash)) {
            throw new IdempotencyConflictException("idempotencyKey was already used with a different payload");
        }
        try {
            return objectMapper.readValue(existing.getResponsePayload(), responseType);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize idempotent response", e);
        }
    }

    private <T> T readCachedResponse(String cacheKey, String payloadHash, Class<T> responseType) {
        try {
            String cachedPayload = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cachedPayload == null) {
                return null;
            }
            CachedIdempotentResponse cached = objectMapper.readValue(cachedPayload, CachedIdempotentResponse.class);
            if (!cached.payloadHash().equals(payloadHash)) {
                throw new IdempotencyConflictException("idempotencyKey was already used with a different payload");
            }
            return objectMapper.readValue(cached.responsePayload(), responseType);
        } catch (IdempotencyConflictException e) {
            throw e;
        } catch (Exception e) {
            log.debug("internal-seats redis read failed for key={}", cacheKey, e);
            return null;
        }
    }

    private void cacheResponsePayload(String cacheKey, String payloadHash, String responsePayload) {
        try {
            stringRedisTemplate.opsForValue().set(
                    cacheKey,
                    objectMapper.writeValueAsString(new CachedIdempotentResponse(payloadHash, responsePayload)),
                    IDEMPOTENCY_RESPONSE_CACHE_TTL
            );
        } catch (Exception e) {
            log.debug("internal-seats redis write failed for key={}", cacheKey, e);
        }
    }

    private String writeResponse(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize idempotent response", e);
        }
    }

    private String hashPayload(Object... values) {
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
            throw new IllegalStateException("Failed to hash idempotency payload", e);
        }
    }

    private String cacheKey(String prefix, UUID eventId, String idempotencyKey) {
        return prefix + eventId + ":" + idempotencyKey;
    }

    private record CachedIdempotentResponse(String payloadHash, String responsePayload) {
    }
}
