package com.example.seats_allocation_service.service;

import com.example.seats_allocation_service.dtos.InventoryPricing;
import com.example.seats_allocation_service.dtos.SeatAvailabilityResponse;
import com.example.seats_allocation_service.exceptions.EventNotFoundException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.models.AllocationIdempotency;
import com.example.seats_allocation_service.models.EventSeat;
import com.example.seats_allocation_service.repository.AllocationIdempotencyRepository;
import com.example.seats_allocation_service.repository.EventInventoryContextRepository;
import com.example.seats_allocation_service.repository.EventSeatRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EventSeatService {
    private static final String OPERATION_SEAT_LOCK = "SEAT_LOCK";
    private static final String EVENT_SEATS_CACHE_KEY_PREFIX = "event:seats:";
    private static final Duration EVENT_SEATS_CACHE_TTL = Duration.ofMinutes(5);
    private static final Duration LOCK_TTL = Duration.ofMinutes(10);
    private static final Duration IDEMPOTENCY_RESPONSE_CACHE_TTL = Duration.ofHours(1);
    private static final String LOCK_RESPONSE_CACHE_KEY_PREFIX = "event:locks:response:";

    private final EventSeatRepository eventSeatRepository;
    private final EventInventoryContextRepository eventInventoryContextRepository;
    private final AllocationIdempotencyRepository allocationIdempotencyRepository;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    @Autowired
    public EventSeatService(
            EventSeatRepository eventSeatRepository,
            EventInventoryContextRepository eventInventoryContextRepository,
            AllocationIdempotencyRepository allocationIdempotencyRepository,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper
    ) {
        this.eventSeatRepository = eventSeatRepository;
        this.eventInventoryContextRepository = eventInventoryContextRepository;
        this.allocationIdempotencyRepository = allocationIdempotencyRepository;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
    }

    public void initializeInventory(UUID eventId, Set<InventoryPricing> pricing) {
        try (MDC.MDCCloseable serviceLogGroup = MDC.putCloseable("logGroup", "internal-event-inventory-init")) {
            log.info("initializeInventory started for eventId={} with pricingCount={}", eventId, pricing.size());
            List<EventSeat> seats = new ArrayList<>(pricing.size());
            for (InventoryPricing item : pricing) {
                EventSeat seat = new EventSeat();
                seat.setEventId(eventId);
                seat.setSectionId(item.getSectionId());
                seat.setVenueSeatId(item.getSectionId());
                seat.setPriceCents(Math.toIntExact(item.getPriceCents()));
                seats.add(seat);
            }
            eventSeatRepository.saveAll(seats);
            log.info("initializeInventory completed for eventId={} with seatsSaved={}", eventId, seats.size());
        }
    }

    public List<EventSeat> getSeats(UUID eventId) {
        try (MDC.MDCCloseable serviceLogGroup = MDC.putCloseable("logGroup", "event-seats-get")) {
            log.info("getSeats started for eventId={}", eventId);
            String cacheKey = EVENT_SEATS_CACHE_KEY_PREFIX + eventId;
            try {
                String cachedPayload = stringRedisTemplate.opsForValue().get(cacheKey);
                if (cachedPayload != null) {
                    List<EventSeat> cachedSeats = objectMapper.readValue(cachedPayload, new TypeReference<>() {
                    });
                    log.info("getSeats cache hit for eventId={} with seatCount={}", eventId, cachedSeats.size());
                    return cachedSeats;
                }
                log.info("getSeats cache miss for eventId={}", eventId);
            } catch (Exception e) {
                log.debug("Cache read failed for eventId={}", eventId, e);
            }

            List<EventSeat> seats = eventSeatRepository.findByEventId(eventId);
            log.info("getSeats db lookup completed for eventId={} with seatCount={}", eventId, seats == null ? 0 : seats.size());
            if (seats == null || seats.isEmpty()) {
                if (!eventInventoryContextRepository.existsById(eventId)) {
                    log.warn("getSeats event not found for eventId={}", eventId);
                    throw new EventNotFoundException("Event not found for eventId: " + eventId);
                }
                log.info("getSeats returning empty seats for existing eventId={}", eventId);
                return seats;
            }

            try {
                String payload = objectMapper.writeValueAsString(seats);
                stringRedisTemplate.opsForValue().set(cacheKey, payload, EVENT_SEATS_CACHE_TTL);
                log.info("getSeats cache write succeeded for eventId={} with seatCount={}", eventId, seats.size());
            } catch (Exception e) {
                log.debug("Cache write failed for eventId={}", eventId, e);
            }

            log.info("getSeats completed for eventId={} with seatCount={}", eventId, seats.size());
            return seats;
        }
    }

    public SeatAvailabilityResponse getAvailabilitySummary(UUID eventId) {
        try (MDC.MDCCloseable serviceLogGroup = MDC.putCloseable("logGroup", "event-seats-availability")) {
            List<EventSeat> seats = eventSeatRepository.findByEventId(eventId);
            if (seats == null || seats.isEmpty()) {
                if (!eventInventoryContextRepository.existsById(eventId)) {
                    throw new EventNotFoundException("Event not found for eventId: " + eventId);
                }
            }

            Instant now = Instant.now();
            int availableSeats = 0;
            int lockedSeats = 0;

            for (EventSeat seat : seats) {
                if (seat.getStatus() == EventSeat.SeatStatus.LOCKED
                        && seat.getLockExpiresAt() != null
                        && seat.getLockExpiresAt().isAfter(now)) {
                    lockedSeats++;
                } else if (seat.getStatus() == EventSeat.SeatStatus.AVAILABLE
                        || (seat.getStatus() == EventSeat.SeatStatus.LOCKED
                        && (seat.getLockExpiresAt() == null
                        || !seat.getLockExpiresAt().isAfter(now)))) {
                    availableSeats++;
                }
            }

            SeatAvailabilityResponse response = new SeatAvailabilityResponse();
            response.setTotalSeats(seats.size());
            response.setAvailableSeats(availableSeats);
            response.setLockedSeats(lockedSeats);
            return response;
        }
    }

    public int getSeatCount(UUID eventId) {
        return eventSeatRepository.countByEventId(eventId);
    }

    @Transactional
    public void lockSeats(UUID eventId, String idempotencyKey, UUID userId, List<UUID> seatIds) throws EventNotFoundException, SeatsNotFoundException, SeatLockConflictException {
        long startTimeNanos = System.nanoTime();
        log.info("lockSeats service started for eventId={} userId={} idempotencyKey={} inputSeatCount={}",
                eventId, userId, idempotencyKey, seatIds == null ? 0 : seatIds.size());
        // De-duplicate incoming seat ids while preserving client order.
        List<UUID> normalizedSeatIds = new ArrayList<>(new LinkedHashSet<>(seatIds));
        String seatIdsHash = hashSeatIds(normalizedSeatIds);
        String cacheKey = cacheKey(eventId, userId, idempotencyKey);
        String internalIdempotencyKey = internalLockIdempotencyKey(userId, idempotencyKey);

        // Fast idempotency replay path via Redis cache.
        Optional<String> cachedRedisResponseMessage = readCachedResponseMessage(cacheKey);
        if (cachedRedisResponseMessage.isPresent()) {
            long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
            log.info("lockSeats service idempotent replay from cache for eventId={} userId={} idempotencyKey={} latencyMs={}",
                    eventId, userId, idempotencyKey, latencyMs);
            return;
        }

        // Durable idempotency check in DB for (eventId, userId, idempotencyKey).
        Optional<AllocationIdempotency> existingIdempotency =
                allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey(OPERATION_SEAT_LOCK, eventId, internalIdempotencyKey);
        if (existingIdempotency.isPresent()) {
            AllocationIdempotency idempotency = existingIdempotency.get();
            // Same key with different seat list is a request conflict.
            if (!idempotency.getPayloadHash().equals(seatIdsHash)) {
                log.warn("lockSeats service idempotency key conflict for eventId={} userId={} idempotencyKey={}",
                        eventId, userId, idempotencyKey);
                throw new SeatLockConflictException("idempotencyKey was already used with a different seat list");
            }
            // Same key with same payload: replay accepted.
            cacheResponsePayload(cacheKey, idempotency.getResponsePayload());
            long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
            log.info("lockSeats service idempotent replay from db for eventId={} userId={} idempotencyKey={} latencyMs={}",
                    eventId, userId, idempotencyKey, latencyMs);
            return;
        }

        // Pessimistically lock requested rows to avoid concurrent updates.
        List<EventSeat> seats = eventSeatRepository.findForUpdateByEventIdAndIds(eventId, normalizedSeatIds);
        // Missing any requested seat means client asked for invalid inventory.
        if (seats.size() != normalizedSeatIds.size()) {
            log.warn("lockSeats service seats not found for eventId={} userId={} idempotencyKey={} requestedSeatCount={} foundSeatCount={}",
                    eventId, userId, idempotencyKey, normalizedSeatIds.size(), seats.size());
            throw new SeatsNotFoundException("One or more requested seats do not exist for this event");
        }

        Map<UUID, EventSeat> seatById = seats.stream().collect(Collectors.toMap(EventSeat::getId, seat -> seat));
        Instant now = Instant.now();
        for (UUID seatId : normalizedSeatIds) {
            EventSeat seat = seatById.get(seatId);
            // Booked seats cannot be locked again.
            if (seat.getStatus() == EventSeat.SeatStatus.BOOKED) {
                log.warn("lockSeats service seat already booked for eventId={} userId={} idempotencyKey={} seatId={}",
                        eventId, userId, idempotencyKey, seatId);
                throw new SeatLockConflictException("Seat " + seatId + " is already booked");
            }
            // Active lock by another user is a conflict.
            if (seat.getStatus() == EventSeat.SeatStatus.LOCKED
                    && seat.getLockExpiresAt() != null
                    && seat.getLockExpiresAt().isAfter(now)
                    && !userId.equals(seat.getLockedBy())) {
                log.warn("lockSeats service seat already locked by another user for eventId={} userId={} idempotencyKey={} seatId={} lockedBy={}",
                        eventId, userId, idempotencyKey, seatId, seat.getLockedBy());
                throw new SeatLockConflictException("Seat " + seatId + " is locked by another user");
            }
        }

        Instant lockExpiresAt = now.plus(LOCK_TTL);
        for (EventSeat seat : seats) {
            seat.setStatus(EventSeat.SeatStatus.LOCKED);
            seat.setLockedBy(userId);
            seat.setLockExpiresAt(lockExpiresAt);
        }
        // Persist lock state atomically inside this transaction.
        eventSeatRepository.saveAll(seats);

        String successMessage = "Locked " + seats.size() + " seat(s) until " + lockExpiresAt;
        String responsePayload = serializeSuccessfulResponsePayload(successMessage);

        AllocationIdempotency idempotency = new AllocationIdempotency();
        idempotency.setOperationType(OPERATION_SEAT_LOCK);
        idempotency.setResourceId(eventId);
        idempotency.setIdempotencyKey(internalIdempotencyKey);
        idempotency.setPayloadHash(seatIdsHash);
        idempotency.setResponsePayload(responsePayload);
        try {
            // Save idempotency outcome so retries can be replayed safely.
            allocationIdempotencyRepository.save(idempotency);
        } catch (DataIntegrityViolationException ex) {
            // Handle race where another request with same key inserted first.
            Optional<AllocationIdempotency> existingAfterRace =
                    allocationIdempotencyRepository.findByOperationTypeAndResourceIdAndIdempotencyKey(OPERATION_SEAT_LOCK, eventId, internalIdempotencyKey);
            if (existingAfterRace.isPresent()) {
                AllocationIdempotency existing = existingAfterRace.get();
                if (!existing.getPayloadHash().equals(seatIdsHash)) {
                    log.warn("lockSeats service idempotency race conflict for eventId={} userId={} idempotencyKey={}",
                            eventId, userId, idempotencyKey);
                    throw new SeatLockConflictException("idempotencyKey was already used with a different seat list");
                }
                cacheResponsePayload(cacheKey, existing.getResponsePayload());
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("lockSeats service idempotent replay after race for eventId={} userId={} idempotencyKey={} latencyMs={}",
                        eventId, userId, idempotencyKey, latencyMs);
                return;
            }
            throw ex;
        }

        cacheResponsePayload(cacheKey, responsePayload);
        long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
        log.info("lockSeats service completed for eventId={} userId={} idempotencyKey={} lockedSeatCount={} latencyMs={}",
                eventId, userId, idempotencyKey, seats.size(), latencyMs);
    }

    @Transactional
    public int releaseLocks(UUID eventId, UUID userId, List<UUID> seatIds) {
        try (MDC.MDCCloseable serviceLogGroup = MDC.putCloseable("logGroup", "event-seats-release")) {
            long startTimeNanos = System.nanoTime();
            log.info("releaseLocks service started for eventId={} userId={} inputSeatCount={}",
                    eventId, userId, seatIds == null ? 0 : seatIds.size());

            List<UUID> normalizedSeatIds = new ArrayList<>(new LinkedHashSet<>(seatIds));
            List<EventSeat> seats = eventSeatRepository.findForUpdateByEventIdAndIds(eventId, normalizedSeatIds);
            if (seats.size() != normalizedSeatIds.size()) {
                if (!eventInventoryContextRepository.existsById(eventId)) {
                    log.warn("releaseLocks service event not found for eventId={} userId={}", eventId, userId);
                    throw new EventNotFoundException("Event not found for eventId: " + eventId);
                }
                log.warn("releaseLocks service seats not found for eventId={} userId={} requestedSeatCount={} foundSeatCount={}",
                        eventId, userId, normalizedSeatIds.size(), seats.size());
                throw new SeatsNotFoundException("One or more requested seats do not exist for this event");
            }

            Instant now = Instant.now();
            int releasedCount = 0;
            for (EventSeat seat : seats) {
                if (seat.getStatus() == EventSeat.SeatStatus.LOCKED
                        && userId.equals(seat.getLockedBy())
                        && seat.getLockExpiresAt() != null
                        && seat.getLockExpiresAt().isAfter(now)) {
                    seat.setStatus(EventSeat.SeatStatus.AVAILABLE);
                    seat.setLockedBy(null);
                    seat.setLockExpiresAt(null);
                    releasedCount++;
                }
            }

            if (releasedCount > 0) {
                eventSeatRepository.saveAll(seats);
            }
            long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
            log.info("releaseLocks service completed for eventId={} userId={} requestedSeatCount={} releasedCount={} latencyMs={}",
                    eventId, userId, normalizedSeatIds.size(), releasedCount, latencyMs);
            return releasedCount;
        }
    }

    private String cacheKey(UUID eventId, UUID userId, String idempotencyKey) {
        return LOCK_RESPONSE_CACHE_KEY_PREFIX + eventId + ":" + userId + ":" + idempotencyKey;
    }

    private String internalLockIdempotencyKey(UUID userId, String idempotencyKey) {
        return userId + ":" + idempotencyKey;
    }

    private Optional<String> readCachedResponseMessage(String cacheKey) {
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cached == null) {
                return Optional.empty();
            }
            Map<String, String> payload = objectMapper.readValue(cached, new TypeReference<>() {
            });
            return Optional.ofNullable(payload.get("message"));
        } catch (Exception e) {
            log.debug("lockSeats redis read failed for key={}", cacheKey, e);
            return Optional.empty();
        }
    }

    private void cacheResponsePayload(String cacheKey, String responsePayload) {
        try {
            stringRedisTemplate.opsForValue().set(cacheKey, responsePayload, IDEMPOTENCY_RESPONSE_CACHE_TTL);
        } catch (Exception e) {
            log.debug("lockSeats redis write failed for key={}", cacheKey, e);
        }
    }

    private String serializeSuccessfulResponsePayload(String message) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "status", "SUCCESS",
                    "message", message
            ));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize lock response", e);
        }
    }

    private String hashSeatIds(List<UUID> seatIds) {
        List<String> sortedSeatIds = seatIds.stream()
                .map(UUID::toString)
                .sorted(Comparator.naturalOrder())
                .toList();
        String canonical = String.join(",", sortedSeatIds);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash seat ids", e);
        }
    }

}
