package com.example.seats_allocation_service.service;

import com.example.seats_allocation_service.dtos.InventoryPricing;
import com.example.seats_allocation_service.dtos.InventoryInitRequest;
import com.example.seats_allocation_service.dtos.InventoryInitRequestedEvent;
import com.example.seats_allocation_service.exceptions.EventInventoryAlreadyExistsException;
import com.example.seats_allocation_service.exceptions.IdempotencyConflictException;
import com.example.seats_allocation_service.models.AllocationIdempotency;
import com.example.seats_allocation_service.models.EventInventoryContext;
import com.example.seats_allocation_service.repository.AllocationIdempotencyRepository;
import com.example.seats_allocation_service.repository.EventInventoryContextRepository;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
public class EventInventoryService {
    private static final String OPERATION_INVENTORY_INIT = "INVENTORY_INIT";
    private static final Duration IDEMPOTENCY_RESPONSE_CACHE_TTL = Duration.ofHours(1);
    private static final String INVENTORY_RESPONSE_CACHE_KEY_PREFIX = "inventory:init:response:";
    private final EventInventoryContextRepository eventInventoryContextRepository;
    private final AllocationIdempotencyRepository allocationIdempotencyRepository;
    private final EventSeatService eventSeatService;
    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;
    private final String defaultCurrency;

    @Autowired
    public EventInventoryService(
            EventInventoryContextRepository eventInventoryContextRepository,
            AllocationIdempotencyRepository allocationIdempotencyRepository,
            EventSeatService eventSeatService,
            StringRedisTemplate stringRedisTemplate,
            ObjectMapper objectMapper,
            @Value("${app.inventory.default-currency:USD}") String defaultCurrency
    ) {
        this.eventInventoryContextRepository = eventInventoryContextRepository;
        this.allocationIdempotencyRepository = allocationIdempotencyRepository;
        this.eventSeatService = eventSeatService;
        this.stringRedisTemplate = stringRedisTemplate;
        this.objectMapper = objectMapper;
        this.defaultCurrency = defaultCurrency;
    }

    public EventInventoryContext initializeInventory(InventoryInitRequestedEvent event) throws EventInventoryAlreadyExistsException {
        String payloadHash = hashPayload(
                event.getEventId(),
                event.getVenueId(),
                event.getInventoryType(),
                event.getSeatMapVersion()
        );
        String cacheKey = cacheKey(event.getEventId(), event.getRequestId());
        EventInventoryContext redisReplay = readCachedResponse(cacheKey, payloadHash, EventInventoryContext.class);
        if (redisReplay != null) {
            return redisReplay;
        }

        AllocationIdempotency existing = allocationIdempotencyRepository
                .findByOperationTypeAndResourceIdAndIdempotencyKey(OPERATION_INVENTORY_INIT, event.getEventId(), event.getRequestId())
                .orElse(null);
        if (existing != null) {
            if (!existing.getPayloadHash().equals(payloadHash)) {
                throw new IdempotencyConflictException("requestId was already used with a different inventory-init payload");
            }
            cacheResponsePayload(cacheKey, existing.getPayloadHash(), existing.getResponsePayload());
            return readResponse(existing.getResponsePayload(), EventInventoryContext.class);
        }

        InventoryInitRequest request = new InventoryInitRequest();
        request.setVenueId(event.getVenueId());
        request.setCurrency(defaultCurrency);
        request.setPricing(Set.<InventoryPricing>of());
        EventInventoryContext savedContext = initializeInventory(event.getEventId(), request);
        cacheIdempotentResponse(OPERATION_INVENTORY_INIT, event.getEventId(), event.getRequestId(), payloadHash, cacheKey, savedContext);
        return savedContext;
    }

    @Transactional
    public EventInventoryContext initializeInventory(UUID eventId, InventoryInitRequest request) throws EventInventoryAlreadyExistsException {
        try (MDC.MDCCloseable serviceLogGroup = MDC.putCloseable("logGroup", "internal-event-inventory-init")) {
            log.info("initializeInventory started for eventId={}", eventId);
            if (eventInventoryContextRepository.existsById(eventId)) {
                throw new EventInventoryAlreadyExistsException("Event inventory context already exists for eventId: " + eventId);
            }
            EventInventoryContext eventInventoryContext = new EventInventoryContext();
            eventInventoryContext.setId(eventId);
            eventInventoryContext.setVenueId(request.getVenueId());
            eventInventoryContext.setCurrency(request.getCurrency());
            EventInventoryContext savedContext = eventInventoryContextRepository.save(eventInventoryContext);

            Set<InventoryPricing> pricing = request.getPricing();
            if (pricing != null && !pricing.isEmpty()) {
                eventSeatService.initializeInventory(eventId, pricing);
            }

            log.info("initializeInventory completed for eventId={}", eventId);
            return savedContext;
        }
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
        } catch (DataIntegrityViolationException ignored) {
            log.info("Idempotency record already persisted for operationType={} resourceId={} idempotencyKey={}",
                    operationType, resourceId, idempotencyKey);
        }
        cacheResponsePayload(cacheKey, payloadHash, responsePayload);
    }

    private <T> T readCachedResponse(String cacheKey, String payloadHash, Class<T> responseType) {
        try {
            String cachedPayload = stringRedisTemplate.opsForValue().get(cacheKey);
            if (cachedPayload == null) {
                return null;
            }
            CachedIdempotentResponse cached = readResponse(cachedPayload, CachedIdempotentResponse.class);
            if (!payloadHash.equals(cached.payloadHash())) {
                throw new IdempotencyConflictException("requestId was already used with a different inventory-init payload");
            }
            return readResponse(cached.responsePayload(), responseType);
        } catch (IdempotencyConflictException e) {
            throw e;
        } catch (Exception e) {
            log.debug("inventory-init redis read failed for key={}", cacheKey, e);
            return null;
        }
    }

    private void cacheResponsePayload(String cacheKey, String payloadHash, String responsePayload) {
        try {
            String finalPayload = writeResponse(new CachedIdempotentResponse(payloadHash, responsePayload));
            stringRedisTemplate.opsForValue().set(cacheKey, finalPayload, IDEMPOTENCY_RESPONSE_CACHE_TTL);
        } catch (Exception e) {
            log.debug("inventory-init redis write failed for key={}", cacheKey, e);
        }
    }

    private String writeResponse(Object response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize idempotent response", e);
        }
    }

    private <T> T readResponse(String payload, Class<T> responseType) {
        try {
            return objectMapper.readValue(payload, responseType);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize idempotent response", e);
        }
    }

    private String hashPayload(Object... values) {
        String canonical = java.util.Arrays.stream(values)
                .map(value -> value == null ? "null" : value.toString())
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash idempotency payload", e);
        }
    }

    private String cacheKey(UUID eventId, String requestId) {
        return INVENTORY_RESPONSE_CACHE_KEY_PREFIX + eventId + ":" + requestId;
    }

    private record CachedIdempotentResponse(String payloadHash, String responsePayload) {
    }
}
