package com.example.seats_allocation_service.repository;

import com.example.seats_allocation_service.models.AllocationIdempotency;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AllocationIdempotencyRepository extends JpaRepository<AllocationIdempotency, UUID> {
    Optional<AllocationIdempotency> findByOperationTypeAndResourceIdAndIdempotencyKey(String operationType, UUID resourceId, String idempotencyKey);

    @Modifying
    @Query(value = """
            INSERT INTO allocation_idempotency (
                id,
                operation_type,
                resource_id,
                idempotency_key,
                payload_hash,
                response_payload,
                created_at,
                updated_at
            )
            VALUES (
                gen_random_uuid(),
                :operationType,
                :resourceId,
                :idempotencyKey,
                :payloadHash,
                CAST(:responsePayload AS jsonb),
                CURRENT_TIMESTAMP,
                CURRENT_TIMESTAMP
            )
            """, nativeQuery = true)
    int insertRecord(
            @Param("operationType") String operationType,
            @Param("resourceId") UUID resourceId,
            @Param("idempotencyKey") String idempotencyKey,
            @Param("payloadHash") String payloadHash,
            @Param("responsePayload") String responsePayload
    );
}
