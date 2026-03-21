package com.example.seats_allocation_service.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import com.example.seats_allocation_service.dtos.LockDetail;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.models.EventSeat;
import com.example.seats_allocation_service.repository.EventSeatRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class LockService {
    private final EventSeatRepository eventSeatRepository;

    public LockDetail getLockDetails(UUID bookingId) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", "internal-locks-get")) {
            long startTimeNanos = System.nanoTime();
            log.info("getLockDetails service started for bookingId={}", bookingId);

            Instant now = Instant.now();
            List<EventSeat> lockedSeats = eventSeatRepository.findByLockedByAndStatus(bookingId, EventSeat.SeatStatus.LOCKED)
                    .stream()
                    .filter(seat -> seat.getLockExpiresAt() != null && seat.getLockExpiresAt().isAfter(now))
                    .sorted(Comparator.comparing(EventSeat::getId))
                    .toList();

            if (lockedSeats.isEmpty()) {
                long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
                log.warn("getLockDetails service found no active locks for bookingId={} latencyMs={}", bookingId, latencyMs);
                throw new SeatsNotFoundException("No active lock found for bookingId: " + bookingId);
            }

            EventSeat firstSeat = lockedSeats.get(0);
            LockDetail lockDetail = LockDetail.builder()
                    .bookingId(bookingId)
                    .eventId(firstSeat.getEventId())
                    .seatIds(lockedSeats.stream().map(EventSeat::getId).toList())
                    .lockExpiresAt(firstSeat.getLockExpiresAt().toString())
                    .status(firstSeat.getStatus().name())
                    .build();

            long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
            log.info("getLockDetails service completed for bookingId={} eventId={} seatCount={} lockExpiresAt={} latencyMs={}",
                    bookingId,
                    lockDetail.getEventId(),
                    lockDetail.getSeatIds().size(),
                    lockDetail.getLockExpiresAt(),
                    latencyMs);
            return lockDetail;
        }
    }
}
