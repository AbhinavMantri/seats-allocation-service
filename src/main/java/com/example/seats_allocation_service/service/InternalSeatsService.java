package com.example.seats_allocation_service.service;

import com.example.seats_allocation_service.dtos.ReleaseReason;
import com.example.seats_allocation_service.dtos.ReleaseSeatsResult;
import com.example.seats_allocation_service.dtos.SeatsConfirmation;
import com.example.seats_allocation_service.exceptions.EventNotFoundException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.models.EventSeat;
import com.example.seats_allocation_service.repository.EventInventoryContextRepository;
import com.example.seats_allocation_service.repository.EventSeatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class InternalSeatsService {
    private final EventSeatRepository eventSeatRepository;
    private final EventInventoryContextRepository eventInventoryContextRepository;

    @Transactional
    public SeatsConfirmation confirmSeats(UUID eventId, UUID bookingId, List<UUID> seatIds, Instant confirmedAt) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", "internal-seats-confirm")) {
            long startTimeNanos = System.nanoTime();
            log.info("confirmSeats service started for eventId={} bookingId={} inputSeatCount={} confirmedAt={}",
                    eventId, bookingId, seatIds == null ? 0 : seatIds.size(), confirmedAt);
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
                    .seatIds(seatIds)
                    .bookedCount(seatsToConfirm.size())
                    .confirmedAt(confirmedAt.toString())
                    .build();
            long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
            log.info("confirmSeats service completed for eventId={} bookingId={} bookedCount={} latencyMs={}",
                    eventId, bookingId, result.getBookedCount(), latencyMs);
            return result;
        }
    }

    @Transactional
    public ReleaseSeatsResult releaseSeats(String eventId, String bookingId, List<String> seatIds, ReleaseReason reason) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable("logGroup", "internal-seats-release")) {
            long startTimeNanos = System.nanoTime();
            UUID parsedEventId = UUID.fromString(eventId);
            UUID parsedBookingId = UUID.fromString(bookingId);
            List<UUID> parsedSeatIds = seatIds.stream()
                    .map(UUID::fromString)
                    .toList();
            log.info("releaseSeats service started for eventId={} bookingId={} inputSeatCount={} reason={}",
                    parsedEventId, parsedBookingId, parsedSeatIds.size(), reason);

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
            long latencyMs = (System.nanoTime() - startTimeNanos) / 1_000_000;
            log.info("releaseSeats service completed for eventId={} bookingId={} releasedCount={} reason={} latencyMs={}",
                    parsedEventId, parsedBookingId, result.getReleasedCount(), reason, latencyMs);
            return result;
        }
    }
}
