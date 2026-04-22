package com.example.seats_allocation_service.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_seats")
@Data
public class EventSeat extends BaseEntity {

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "venue_seat_id", nullable = false)
    private UUID venueSeatId;

    @Column(name = "section_id", nullable = false)
    private UUID sectionId;

    @Column(name = "price_cents", nullable = false)
    private Integer priceCents;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Column(name = "locked_by")
    private UUID lockedBy;

    @Column(name = "lock_expires_at")
    private Instant lockExpiresAt;

    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "booked_at")
    private Instant bookedAt;

    @PrePersist
    private void prePersist() {
        if (status == null) {
            status = SeatStatus.AVAILABLE;
        }
    }

    public enum SeatStatus {
        AVAILABLE,
        LOCKED,
        BOOKED
    }
}
