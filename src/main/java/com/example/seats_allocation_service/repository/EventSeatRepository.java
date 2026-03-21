package com.example.seats_allocation_service.repository;

import com.example.seats_allocation_service.models.EventSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;

public interface EventSeatRepository extends JpaRepository<EventSeat, UUID> {
    List<EventSeat> findByEventId(UUID eventId);
    List<EventSeat> findByLockedByAndStatus(UUID lockedBy, EventSeat.SeatStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from EventSeat s where s.eventId = :eventId and s.id in :seatIds")
    List<EventSeat> findForUpdateByEventIdAndIds(@Param("eventId") UUID eventId, @Param("seatIds") List<UUID> seatIds);
}
