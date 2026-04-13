package com.example.seats_allocation_service.controllers;

import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.seats_allocation_service.dtos.LockDetail;
import com.example.seats_allocation_service.dtos.LockDetailResponse;
import com.example.seats_allocation_service.dtos.common.ResponseStatus;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.service.LockService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/internal/locks")
@RequiredArgsConstructor
@Slf4j
public class LockController {
    private final LockService lockService;
    
    @GetMapping("")
    public ResponseEntity<LockDetailResponse> getLockDetails(@RequestParam UUID bookingId) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", "internal-locks-get")) {
            long startTimeNanos = System.nanoTime();
            log.info("getLockDetails request received for bookingId={}", bookingId);

            LockDetailResponse response = new LockDetailResponse();
            try {
                LockDetail lockDetail = lockService.getLockDetails(bookingId);
                response.setResult(lockDetail);
                response.setStatus(ResponseStatus.SUCCESS);
                response.setMessage("Lock details fetched successfully");
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.info("getLockDetails succeeded for bookingId={} eventId={} seatCount={} latencyMs={}",
                        bookingId,
                        lockDetail.getEventId(),
                        lockDetail.getSeats() == null ? 0 : lockDetail.getSeats().size(),
                        latencyMs);
                return ResponseEntity.ok(response);
            } catch (SeatsNotFoundException e) {
                response.setMessage(e.getMessage());
                response.setStatus(ResponseStatus.FAILURE);
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("getLockDetails failed for bookingId={} reason={} latencyMs={}",
                        bookingId, e.getMessage(), latencyMs);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
        }
    }
}
