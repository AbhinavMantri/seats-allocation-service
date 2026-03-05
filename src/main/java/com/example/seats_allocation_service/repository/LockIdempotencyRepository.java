package com.example.seats_allocation_service.repository;

import com.example.seats_allocation_service.models.LockIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface LockIdempotencyRepository extends JpaRepository<LockIdempotency, UUID> {
    Optional<LockIdempotency> findByEventIdAndUserIdAndIdempotencyKey(UUID eventId, UUID userId, String idempotencyKey);
}
