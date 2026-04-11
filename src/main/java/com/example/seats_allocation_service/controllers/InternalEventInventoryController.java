package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.dtos.InventoryInitializedEvent;
import com.example.seats_allocation_service.dtos.InventoryInitRequestedEvent;
import com.example.seats_allocation_service.exceptions.EventInventoryAlreadyExistsException;
import com.example.seats_allocation_service.service.EventInventoryService;
import com.example.seats_allocation_service.service.EventSeatService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class InternalEventInventoryController {

    private final EventInventoryService eventInventoryService;
    private final EventSeatService eventSeatService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    @Value("${app.kafka.topics.inventory-init-result:inventory-published.v1}")
    private String inventoryInitResultTopic;

    @KafkaListener(
            topics = "${app.kafka.topics.inventory-init-request:inventory-init.v1}",
            groupId = "${spring.application.name}"
    )
    public void initializeInventory(String payload) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", "inventory-init.v1")) {
            long startTimeNanos = System.nanoTime();
            InventoryInitRequestedEvent event = readEvent(payload);
            log.info("Inventory init event received for requestId={} eventId={} venueId={} inventoryType={} seatMapVersion={}",
                    event.getRequestId(), event.getEventId(), event.getVenueId(), event.getInventoryType(), event.getSeatMapVersion());
            try {
                eventInventoryService.initializeInventory(event);
                publishInventoryInitialized(event);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("Inventory init event processed for requestId={} eventId={} latencyMs={}",
                        event.getRequestId(), event.getEventId(), latencyMs);
            } catch (EventInventoryAlreadyExistsException e) {
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("Inventory init event skipped for requestId={} eventId={} reason={} latencyMs={}",
                        event.getRequestId(), event.getEventId(), e.getMessage(), latencyMs);
            }
        }
    }

    private InventoryInitRequestedEvent readEvent(String payload) {
        try {
            return objectMapper.readValue(payload, InventoryInitRequestedEvent.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse inventory-init.v1 payload", e);
        }
    }

    private void publishInventoryInitialized(InventoryInitRequestedEvent event) {
        try {
            InventoryInitializedEvent resultEvent = InventoryInitializedEvent.builder()
                    .requestId(event.getRequestId())
                    .eventId(event.getEventId())
                    .status("SUCCESS")
                    .totalSeats(eventSeatService.getSeatCount(event.getEventId()))
                    .seatMapVersion(event.getSeatMapVersion())
                    .processedAt(Instant.now())
                    .build();
            String payload = objectMapper.writeValueAsString(resultEvent);
            kafkaTemplate.send(inventoryInitResultTopic, event.getEventId().toString(), payload);
            log.info("Published inventory initialized event for requestId={} eventId={} topic={}",
                    event.getRequestId(), event.getEventId(), inventoryInitResultTopic);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish inventory-init result event", e);
        }
    }
}
