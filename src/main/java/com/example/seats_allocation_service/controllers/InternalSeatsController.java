package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.dtos.LockSeatResponse;
import com.example.seats_allocation_service.dtos.LockSeatsRequest;
import com.example.seats_allocation_service.dtos.ReleaseLocksRequest;
import com.example.seats_allocation_service.dtos.ReleaseSeatsRequest;
import com.example.seats_allocation_service.dtos.ReleaseSeatsResponse;
import com.example.seats_allocation_service.dtos.ReleaseSeatsResult;
import com.example.seats_allocation_service.dtos.SeatConfirmRequest;
import com.example.seats_allocation_service.dtos.SeatConfirmResponse;
import com.example.seats_allocation_service.dtos.SeatsConfirmation;
import com.example.seats_allocation_service.dtos.common.ApiResponse;
import com.example.seats_allocation_service.dtos.common.ResponseStatus;
import com.example.seats_allocation_service.exceptions.EventNotFoundException;
import com.example.seats_allocation_service.exceptions.IdempotencyConflictException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.service.InternalSeatsService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/seats")
@RequiredArgsConstructor
@Slf4j
public class InternalSeatsController {
    private final InternalSeatsService internalSeatsService;

    @PostMapping("/{eventId}/locks")
    public ResponseEntity<LockSeatResponse> lockSeats(
            @PathVariable UUID eventId,
            @RequestBody @Valid LockSeatsRequest request
    ) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", "internal-seats-lock")) {
            long startTimeNanos = System.nanoTime();
            UUID lockOwnerId = request.getBookingId() == null ? request.getUserId() : request.getBookingId();
            log.info("lockSeats request received for eventId={} lockOwnerId={} requestedSeatCount={} idempotencyKey={}",
                    eventId, lockOwnerId, request.getSeatIds() == null ? 0 : request.getSeatIds().size(), request.getIdempotencyKey());
            LockSeatResponse response = new LockSeatResponse();
            try {
                internalSeatsService.lockSeats(eventId, request.getIdempotencyKey(), lockOwnerId, request.getSeatIds());
                response.setStatus(ResponseStatus.SUCCESS);
                response.setMessage("Seats locked successfully");
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("lockSeats succeeded for eventId={} lockOwnerId={} idempotencyKey={} latencyMs={}",
                        eventId, lockOwnerId, request.getIdempotencyKey(), latencyMs);
                return ResponseEntity.ok(response);
            } catch (EventNotFoundException e) {
                response.setMessage(e.getMessage());
                response.setStatus(ResponseStatus.FAILURE);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("lockSeats failed: event not found for eventId={} lockOwnerId={} idempotencyKey={} reason={} latencyMs={}",
                        eventId, lockOwnerId, request.getIdempotencyKey(), e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            } catch (SeatsNotFoundException | SeatLockConflictException e) {
                response.setMessage(e.getMessage());
                response.setStatus(ResponseStatus.FAILURE);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("lockSeats failed: conflict for eventId={} lockOwnerId={} idempotencyKey={} reason={} latencyMs={}",
                        eventId, lockOwnerId, request.getIdempotencyKey(), e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
        }
    }

    @PostMapping("/confirm")
    public ResponseEntity<SeatConfirmResponse> confirmSeats(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid SeatConfirmRequest request
    ) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", "internal-seats-confirm")) {
            long startTimeNanos = System.nanoTime();
            log.info("confirmSeats request received for eventId={} bookingId={} requestedSeatCount={} confirmedAt={} idempotencyKey={}",
                    request.getEventId(),
                    request.getBookingId(),
                    request.getSeatIds() == null ? 0 : request.getSeatIds().size(),
                    request.getConfirmedAt(),
                    idempotencyKey);
            SeatConfirmResponse response = new SeatConfirmResponse();
            try {
                SeatsConfirmation confirmedSeatsCount = internalSeatsService.confirmSeats(
                        idempotencyKey,
                        request.getEventId(),
                        request.getBookingId(),
                        request.getPaymentId(),
                        request.getSeatIds(),
                        request.getConfirmedAt()
                );
                response.setStatus(ResponseStatus.SUCCESS);
                response.setMessage("Confirmed " + confirmedSeatsCount.getBookedCount() + " seat(s)");
                response.setSeatConfirmation(confirmedSeatsCount);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("confirmSeats succeeded for eventId={} bookingId={} bookedCount={} latencyMs={}",
                        request.getEventId(), request.getBookingId(), confirmedSeatsCount.getBookedCount(), latencyMs);
                return ResponseEntity.ok(response);
            } catch (EventNotFoundException e) {
                response.setMessage(e.getMessage());
                response.setStatus(ResponseStatus.FAILURE);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("confirmSeats failed: event not found for eventId={} bookingId={} reason={} latencyMs={}",
                        request.getEventId(), request.getBookingId(), e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            } catch (SeatsNotFoundException | SeatLockConflictException | IdempotencyConflictException e) {
                response.setMessage(e.getMessage());
                response.setStatus(ResponseStatus.FAILURE);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("confirmSeats failed: conflict for eventId={} bookingId={} reason={} latencyMs={}",
                        request.getEventId(), request.getBookingId(), e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
        }
    }

    @PostMapping("/{eventId}/locks/release")
    public ResponseEntity<ApiResponse> releaseLocks(
            @PathVariable UUID eventId,
            @RequestBody @Valid ReleaseLocksRequest request
    ) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", "internal-seats-lock-release")) {
            long startTimeNanos = System.nanoTime();
            log.info("releaseLocks request received for eventId={} userId={} requestedSeatCount={}",
                    eventId, request.getUserId(), request.getSeatIds() == null ? 0 : request.getSeatIds().size());
            ApiResponse response = new ApiResponse();
            try {
                int releasedCount = internalSeatsService.releaseLocks(eventId, request.getUserId(), request.getSeatIds());
                response.setStatus(ResponseStatus.SUCCESS);
                response.setMessage("Released " + releasedCount + " seat lock(s)");
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("releaseLocks succeeded for eventId={} userId={} releasedCount={} latencyMs={}",
                        eventId, request.getUserId(), releasedCount, latencyMs);
                return ResponseEntity.ok(response);
            } catch (EventNotFoundException e) {
                response.setMessage(e.getMessage());
                response.setStatus(ResponseStatus.FAILURE);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("releaseLocks failed: event not found for eventId={} userId={} reason={} latencyMs={}",
                        eventId, request.getUserId(), e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            } catch (SeatsNotFoundException e) {
                response.setMessage(e.getMessage());
                response.setStatus(ResponseStatus.FAILURE);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("releaseLocks failed: seats not found for eventId={} userId={} reason={} latencyMs={}",
                        eventId, request.getUserId(), e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
        }
    }

    @PostMapping("/release")
    public ResponseEntity<ReleaseSeatsResponse> releaseSeats(
            @RequestHeader("X-Idempotency-Key") String idempotencyKey,
            @RequestBody @Valid ReleaseSeatsRequest request
    ) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", "internal-seats-release")) {
            long startTimeNanos = System.nanoTime();
            log.info("releaseSeats request received for eventId={} bookingId={} requestedSeatCount={} reason={} idempotencyKey={}",
                    request.getEventId(),
                    request.getBookingId(),
                    request.getSeatIds() == null ? 0 : request.getSeatIds().size(),
                    request.getReason(),
                    idempotencyKey);
            ReleaseSeatsResponse response = new ReleaseSeatsResponse();
            try {
                ReleaseSeatsResult releaseResult = internalSeatsService.releaseSeats(
                        idempotencyKey,
                        request.getEventId(),
                        request.getBookingId(),
                        request.getSeatIds(),
                        request.getReason()
                );
                response.setStatus(ResponseStatus.SUCCESS);
                response.setMessage("Released " + releaseResult.getReleasedCount() + " seat(s)");
                response.setResult(releaseResult);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("releaseSeats succeeded for eventId={} bookingId={} releasedCount={} latencyMs={}",
                        request.getEventId(), request.getBookingId(), releaseResult.getReleasedCount(), latencyMs);
                return ResponseEntity.ok(response);
            } catch (EventNotFoundException e) {
                response.setMessage(e.getMessage());
                response.setStatus(ResponseStatus.FAILURE);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("releaseSeats failed: event not found for eventId={} bookingId={} reason={} latencyMs={}",
                        request.getEventId(), request.getBookingId(), e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            } catch (SeatsNotFoundException | SeatLockConflictException | IdempotencyConflictException e) {
                response.setMessage(e.getMessage());
                response.setStatus(ResponseStatus.FAILURE);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("releaseSeats failed: conflict for eventId={} bookingId={} reason={} latencyMs={}",
                        request.getEventId(), request.getBookingId(), e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
            }
        }
    }
}
