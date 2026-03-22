package com.example.seats_allocation_service.repository;

import com.example.seats_allocation_service.models.AllocationIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AllocationIdempotencyRepository extends JpaRepository<AllocationIdempotency, UUID> {
    Optional<AllocationIdempotency> findByOperationTypeAndResourceIdAndIdempotencyKey(String operationType, UUID resourceId, String idempotencyKey);
}
