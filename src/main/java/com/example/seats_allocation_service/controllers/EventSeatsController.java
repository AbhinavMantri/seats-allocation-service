package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.dtos.EventListResponse;
import com.example.seats_allocation_service.dtos.LockSeatResponse;
import com.example.seats_allocation_service.dtos.LockSeatsRequest;
import com.example.seats_allocation_service.dtos.ReleaseLocksRequest;
import com.example.seats_allocation_service.dtos.common.ApiResponse;
import com.example.seats_allocation_service.dtos.common.ResponseStatus;
import com.example.seats_allocation_service.exceptions.EventNotFoundException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.models.EventSeat;
import com.example.seats_allocation_service.service.EventSeatService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/events/{eventId}")
@Slf4j
public class EventSeatsController {
    private final EventSeatService eventSeatService;

    @Autowired
    public EventSeatsController(EventSeatService eventSeatService) {
        this.eventSeatService = eventSeatService;
    }

    @GetMapping("/seats")
    public ResponseEntity<EventListResponse> getSeats(@PathVariable UUID eventId) {
        return withLogGroup("event-seats-get", () -> {
            long startTimeNanos = System.nanoTime();
            log.info("getSeats request received for eventId={}", eventId);
            EventListResponse response = new EventListResponse();
            try {
                List<EventSeat> seats = eventSeatService.getSeats(eventId);
                response.setSeats(seats);
                response.setStatus(ResponseStatus.SUCCESS);
                response.setMessage("Seat availability fetched successfully");
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("getSeats succeeded for eventId={} with seatCount={} latencyMs={}", eventId, seats == null ? 0 : seats.size(), latencyMs);
                return ResponseEntity.ok(response);
            } catch (EventNotFoundException e) {
                response.setStatus(ResponseStatus.FAILURE);
                response.setMessage(e.getMessage());
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("getSeats failed for eventId={} reason={} latencyMs={}", eventId, e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
        });
    }

    @PostMapping("/locks")
    public ResponseEntity<LockSeatResponse> lockSeats(
            @PathVariable UUID eventId,
            @RequestBody @Valid LockSeatsRequest request
    ) {
        return withLogGroup("event-seats-lock", () -> {
            long startTimeNanos = System.nanoTime();
            log.info("lockSeats request received for eventId={} userId={} requestedSeatCount={} idempotencyKey={}",
                    eventId, request.getUserId(), request.getSeatIds() == null ? 0 : request.getSeatIds().size(), request.getIdempotencyKey());
            LockSeatResponse response = new LockSeatResponse();
            try {
                eventSeatService.lockSeats(eventId, request.getIdempotencyKey(), request.getUserId(), request.getSeatIds());
                response.setStatus(ResponseStatus.SUCCESS);
                response.setMessage("Seats locked successfully");
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("lockSeats succeeded for eventId={} userId={} idempotencyKey={} latencyMs={}",
                        eventId, request.getUserId(), request.getIdempotencyKey(), latencyMs);
            } catch (EventNotFoundException e) {
                setFailureResponse(e.getMessage(), response);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("lockSeats failed: event not found for eventId={} userId={} idempotencyKey={} reason={} latencyMs={}",
                        eventId, request.getUserId(), request.getIdempotencyKey(), e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            } catch (SeatsNotFoundException | SeatLockConflictException e) {
                setFailureResponse(e.getMessage(), response);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("lockSeats failed: conflict for eventId={} userId={} idempotencyKey={} reason={} latencyMs={}",
                        eventId, request.getUserId(), request.getIdempotencyKey(), e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
            return ResponseEntity.ok(response);
        });
    }

    @PostMapping("/locks/release")
    public ResponseEntity<ApiResponse> releaseLocks(
            @PathVariable UUID eventId,
            @RequestBody @Valid ReleaseLocksRequest request
    ) {
        return withLogGroup("event-seats-release", () -> {
            long startTimeNanos = System.nanoTime();
            log.info("releaseLocks request received for eventId={} userId={} requestedSeatCount={}",
                    eventId, request.getUserId(), request.getSeatIds() == null ? 0 : request.getSeatIds().size());
            ApiResponse response = new ApiResponse();
            try {
                int releasedCount = eventSeatService.releaseLocks(eventId, request.getUserId(), request.getSeatIds());
                response.setStatus(ResponseStatus.SUCCESS);
                response.setMessage("Released " + releasedCount + " seat lock(s)");
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("releaseLocks succeeded for eventId={} userId={} releasedCount={} latencyMs={}",
                        eventId, request.getUserId(), releasedCount, latencyMs);
                return ResponseEntity.ok(response);
            } catch (EventNotFoundException e) {
                setFailureResponse(e.getMessage(), response);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("releaseLocks failed: event not found for eventId={} userId={} reason={} latencyMs={}",
                        eventId, request.getUserId(), e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            } catch (SeatsNotFoundException e) {
                setFailureResponse(e.getMessage(), response);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("releaseLocks failed: seats not found for eventId={} userId={} reason={} latencyMs={}",
                        eventId, request.getUserId(), e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
        });
    }
    
    private <T extends ApiResponse> void setFailureResponse(String message, T response) {
        response.setStatus(ResponseStatus.FAILURE);
        response.setMessage(message);
    }

    private <T> T withLogGroup(String logGroup, Supplier<T> operation) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", logGroup)) {
            return operation.get();
        }
    }
}
