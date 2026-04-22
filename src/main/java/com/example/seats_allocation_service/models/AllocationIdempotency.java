package com.example.seats_allocation_service.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.util.UUID;

@Entity
@Table(
        name = "allocation_idempotency",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_allocation_idempotency_operation_resource_key", columnNames = {"operation_type", "resource_id", "idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_allocation_idempotency_operation_resource", columnList = "operation_type, resource_id, created_at")
        }
)
@Data
public class AllocationIdempotency extends BaseEntity {
    @Column(name = "operation_type", nullable = false, length = 40)
    private String operationType;

    @Column(name = "resource_id", nullable = false)
    private UUID resourceId;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "payload_hash", nullable = false, length = 128)
    private String payloadHash;

    @Column(name = "response_payload", nullable = false, columnDefinition = "jsonb")
    private String responsePayload;
}
