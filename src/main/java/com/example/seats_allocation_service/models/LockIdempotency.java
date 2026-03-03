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
        name = "lock_idempotency",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_lock_idempotency_event_user_key", columnNames = {"event_id", "user_id", "idempotency_key"})
        },
        indexes = {
                @Index(name = "idx_lock_idempotency_user", columnList = "user_id, created_at")
        }
)
@Data
public class LockIdempotency extends BaseEntity {

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "seat_ids_hash", nullable = false, length = 128)
    private String seatIdsHash;

    @Column(name = "response_payload", nullable = false, columnDefinition = "jsonb")
    private String responsePayload;
}
