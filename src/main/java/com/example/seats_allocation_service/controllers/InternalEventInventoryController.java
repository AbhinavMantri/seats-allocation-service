package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.dtos.InventoryInitRequest;
import com.example.seats_allocation_service.dtos.InventoryInitResponse;
import com.example.seats_allocation_service.dtos.common.ApiResponse;
import com.example.seats_allocation_service.dtos.common.ResponseStatus;
import com.example.seats_allocation_service.exceptions.EventInventoryAlreadyExistsException;
import com.example.seats_allocation_service.models.EventInventoryContext;
import com.example.seats_allocation_service.service.EventInventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/internal/events/{eventId}")
@RequiredArgsConstructor
@Slf4j
public class InternalEventInventoryController {

    private final EventInventoryService eventInventoryService;

    @PostMapping("/inventory/init")
    public ResponseEntity<InventoryInitResponse> initializeInventory(
            @PathVariable UUID eventId,
            @RequestBody @Valid InventoryInitRequest request
    ) {
        return withLogGroup("internal-event-inventory-init", () -> {
            long startTimeNanos = System.nanoTime();
            log.info("Inventory initialization request received for eventId={}", eventId);
            InventoryInitResponse response = new InventoryInitResponse();
            try {
                EventInventoryContext savedContext = eventInventoryService.initializeInventory(eventId, request);
                response.setEventInventoryContext(savedContext);
                response.setStatus(ResponseStatus.SUCCESS);
                response.setMessage("Event initiated successfully");
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("Inventory initialization completed for eventId={} latencyMs={}", eventId, latencyMs);
                return ResponseEntity.ok(response);
            } catch (EventInventoryAlreadyExistsException e) {
                response.setStatus(ResponseStatus.FAILURE);
                response.setMessage(e.getMessage());
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("Inventory initialization conflict for eventId={} reason={} latencyMs={}", eventId, e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
        });
    }

    private <T> T withLogGroup(String logGroup, Supplier<T> operation) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", logGroup)) {
            return operation.get();
        }
    }
}
