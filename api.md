# Seat Allocation Service API

## Overview
The **Seat Allocation Service** manages event seat inventory and reservation lifecycle. It exposes internal APIs for inventory initialization, seat locking, confirmation, release, and query APIs for seat availability and layout.

This document covers:
- REST endpoints
- request/response contracts
- validation rules
- error model
- idempotency expectations
- async Kafka event contracts relevant to this service

---

## Base Information

- **Service Name:** seat-allocation-service
- **Protocol:** HTTP/HTTPS
- **Data Format:** JSON
- **Auth Type:**
  - Internal APIs: service-to-service authentication (JWT / mTLS / API gateway)
  - Read/query APIs: public or protected depending on product requirements
- **Base Path (current implementation):**
  ```text
  /
  ```

---

## Domain Responsibilities

- Create seat inventory for a newly created event
- Expose seat map and availability
- Temporarily lock seats during checkout
- Confirm seats after payment success
- Release seats on failure, timeout, or cancellation
- Support idempotent internal processing
- Publish allocation state changes asynchronously when needed

---

## Seat State Model

| State | Meaning |
|---|---|
| AVAILABLE | Seat is free and can be selected |
| LOCKED | Seat is temporarily reserved for an in-progress booking |
| BOOKED | Seat is sold / confirmed |
| BLOCKED | Seat is not sellable due to ops/maintenance/business rule |
| RELEASED | Logical transition state for audit/eventing; usually becomes AVAILABLE again |

---

## Common Headers

### Required for Internal APIs
```http
Content-Type: application/json
Authorization: Bearer <service-token>
X-Request-Id: <unique-request-id>
X-Idempotency-Key: <unique-idempotency-key>   // required for write APIs
```

### Recommended for Public Query APIs
```http
Content-Type: application/json
X-Request-Id: <unique-request-id>
```

---

## Standard Error Response

```json
{
  "timestamp": "2026-03-16T15:10:45Z",
  "path": "/internal/seats/{eventId}/locks",
  "errorCode": "SEAT_ALREADY_LOCKED",
  "message": "One or more seats are not available for locking",
  "requestId": "req_123456",
  "details": [
    {
      "field": "seatIds",
      "value": "A12",
      "reason": "Seat is already booked"
    }
  ]
}
```

### Common Error Codes

| HTTP Status | Error Code | Meaning |
|---|---|---|
| 400 | INVALID_REQUEST | Request payload is malformed or invalid |
| 400 | INVALID_SEAT_STATE | Requested operation is not allowed for current seat state |
| 401 | UNAUTHORIZED | Missing or invalid authentication |
| 403 | FORBIDDEN | Caller is not allowed to access resource |
| 404 | EVENT_NOT_FOUND | Event inventory does not exist |
| 404 | SEAT_NOT_FOUND | One or more seats do not exist |
| 409 | INVENTORY_ALREADY_INITIALIZED | Inventory already exists for the event |
| 409 | SEAT_ALREADY_LOCKED | Seat already locked |
| 409 | SEAT_ALREADY_BOOKED | Seat already booked |
| 409 | IDEMPOTENCY_CONFLICT | Same idempotency key used with different payload |
| 422 | LOCK_EXPIRED | Seat lock has expired |
| 429 | TOO_MANY_REQUESTS | Rate limit exceeded |
| 500 | INTERNAL_ERROR | Unexpected server error |
| 503 | DEPENDENCY_UNAVAILABLE | Required dependency unavailable |

---

# API Endpoints

## 1. Get Seat Availability Summary

Returns aggregated seat counts for an event.

### Endpoint
```http
GET /events/{eventId}/seats/availability
```

### Path Params

| Param | Type | Required | Description |
|---|---|---|---|
| eventId | string | yes | Event identifier |

### Success Response
**HTTP 200 OK**
```json
{
  "status": "SUCCESS",
  "message": "Seat availability summary fetched successfully",
  "totalSeats": 500,
  "availableSeats": 420,
  "lockedSeats": 30
}
```

---

## 2. Get Seat Map

Returns seat-level state for rendering a seat map UI.

### Endpoint
```http
GET /events/{eventId}/seats
```

### Optional Query Params

| Param | Type | Description |
|---|---|---|
| sectionId | string | Filter by section |
| includeLockedBySelf | boolean | Whether to include caller-owned locks specially |
| page | integer | Pagination for large venues |
| size | integer | Page size |

### Success Response
```json
{
  "status": "SUCCESS",
  "message": "Seat availability fetched successfully",
  "seats": [
    {
      "id": "5f84b7a0-2d91-4db6-bd54-43b2c2a4337f",
      "eventId": "9c9a7b0f-bf09-4f91-9235-4a4bbf34f97b",
      "venueSeatId": "5f0f9e2e-1e6b-4703-8db2-64e8ce20f302",
      "sectionId": "6d4f7126-55f3-4a96-b4c0-c592b6eefb8d",
      "priceCents": 2200,
      "status": "AVAILABLE",
      "lockedBy": null,
      "lockExpiresAt": null,
      "bookingId": null,
      "bookedAt": null,
      "createdAt": "2026-03-16T15:30:00Z",
      "updatedAt": "2026-03-16T15:30:00Z"
    }
  ]
}
```

---

## 3. Lock Seats

Temporarily reserves seats for a booking attempt.

### Endpoint
```http
POST /internal/seats/{eventId}/locks
```

### Behavior
- Lock only if all requested seats are currently available
- Lock duration is configurable, for example 10 minutes
- Should be atomic for the requested seat set
- Booking service should call this before payment initiation

### Request
```json
{
  "eventId": "evt_1001",
  "bookingId": "bk_9001",
  "userId": "usr_77",
  "seatIds": ["A1", "A2", "A3"],
  "lockDurationSeconds": 600,
  "channel": "WEB"
}
```

### Request Fields

| Field | Type | Required | Description |
|---|---|---|---|
| eventId | string | yes | Event identifier |
| bookingId | string | yes | Booking/cart identifier |
| userId | string | no | End user identifier |
| seatIds | array[string] | yes | Seat ids to lock |
| lockDurationSeconds | integer | no | Lock TTL |
| channel | string | no | Booking channel |

### Success Response
**HTTP 200 OK**
```json
{
  "status": "SUCCESS",
  "message": "Seats locked successfully"
}
```

### Failure Example
**HTTP 409 Conflict**
```json
{
  "timestamp": "2026-03-16T15:41:00Z",
  "path": "/internal/seats/{eventId}/locks",
  "errorCode": "SEAT_ALREADY_BOOKED",
  "message": "Requested seats are not available",
  "requestId": "req_lock_100",
  "details": [
    {
      "field": "seatIds",
      "value": "A2",
      "reason": "BOOKED"
    }
  ]
}
```

---

## 4. Confirm Seats

Transitions locked seats to booked after successful payment.

### Endpoint
```http
POST /internal/seats/confirm
```

### Request
```json
{
  "eventId": "evt_1001",
  "bookingId": "bk_9001",
  "paymentId": "pay_441",
  "seatIds": ["A1", "A2", "A3"],
  "confirmedAt": "2026-03-16T15:44:10Z"
}
```

### Rules
- Seats must be in `LOCKED` state
- Lock must belong to the same `bookingId`
- Expired locks should fail unless explicit recovery logic exists

### Success Response
```json
{
  "status": "SUCCESS",
  "message": "Confirmed 3 seat(s)",
  "seatConfirmation": {
    "eventId": "9c9a7b0f-bf09-4f91-9235-4a4bbf34f97b",
    "bookingId": "ce10f1d8-f7c5-4e0a-a6d5-6c40b3376c0f",
    "paymentId": "2b9bd4d4-7b48-4f89-9339-31dc9ab52c91",
    "seatIds": [
      "5f84b7a0-2d91-4db6-bd54-43b2c2a4337f",
      "b3cd59be-f47c-4513-91f2-5ef97afac5b9",
      "dcab5f9f-5847-4f52-82df-f2616cbe0e39"
    ],
    "bookedCount": 3,
    "confirmedAt": "2026-03-16T15:44:10Z"
  }
}
```

---

## 5. Release Seats

Releases seats back to sellable inventory. This endpoint is used both for:
- releasing previously locked seats due to payment failure, user timeout, or cart expiration
- cancelling already booked seats during order cancellation or refund workflows

### Endpoint
```http
POST /internal/seats/release
```

### Request
```json
{
  "eventId": "evt_1001",
  "bookingId": "bk_9001",
  "seatIds": ["A1", "A2", "A3"],
  "reason": "PAYMENT_FAILED"
}
```

### Allowed Reasons
- `PAYMENT_FAILED`
- `BOOKING_EXPIRED`
- `BOOKING_CANCELLED`
- `ORDER_CANCELLED`
- `SYSTEM_RECOVERY`
- `PAYMENT_TIMEOUT`
- `MANUAL_RELEASE`
- `INVENTORY_ROLLBACK`

### Success Response
```json
{
  "status": "SUCCESS",
  "message": "Released 3 seat(s)",
  "result": {
    "eventId": "9c9a7b0f-bf09-4f91-9235-4a4bbf34f97b",
    "bookingId": "ce10f1d8-f7c5-4e0a-a6d5-6c40b3376c0f",
    "seatIds": [
      "5f84b7a0-2d91-4db6-bd54-43b2c2a4337f",
      "b3cd59be-f47c-4513-91f2-5ef97afac5b9",
      "dcab5f9f-5847-4f52-82df-f2616cbe0e39"
    ],
    "releasedCount": 3
  }
}
```

---

### Notes
- this endpoint currently handles both active lock release and booked-seat cancellation semantics
- seats are releasable when they are either `LOCKED` for the booking or `BOOKED` for the booking
- successful release sets the seat state back to `AVAILABLE`

---

## 6. Release Active Locks

Releases active seat locks for a given event and user without using the booking-based release flow.

### Endpoint
```http
POST /internal/seats/{eventId}/locks/release
```

### Success Response
```json
{
  "status": "SUCCESS",
  "message": "Released 2 seat lock(s)"
}
```

---

## 7. Get Lock Status by Booking

Returns currently locked seats for a booking/cart.

### Endpoint
```http
GET /internal/locks?bookingId={bookingId}
```

### Success Response
```json
{
  "status": "SUCCESS",
  "message": "Lock details fetched successfully",
  "result": {
    "bookingId": "ce10f1d8-f7c5-4e0a-a6d5-6c40b3376c0f",
    "eventId": "9c9a7b0f-bf09-4f91-9235-4a4bbf34f97b",
    "seatIds": [
      "5f84b7a0-2d91-4db6-bd54-43b2c2a4337f",
      "b3cd59be-f47c-4513-91f2-5ef97afac5b9",
      "dcab5f9f-5847-4f52-82df-f2616cbe0e39"
    ],
    "lockExpiresAt": "2026-03-16T15:50:00Z",
    "status": "LOCKED"
  }
}
```

---

## 8. Health Check

### Endpoint
```http
GET /actuator/health
```

### Sample Response
```json
{
  "status": "UP"
}
```

---

# Validation Rules

## Inventory Initialization
- `eventId` must be non-empty
- `venueId` must be non-empty
- `inventoryType` must be valid enum
- duplicate seat ids within same event are not allowed
- initialization is allowed only once unless explicit re-init endpoint is introduced
- inbound inventory initialization is handled via Kafka, not REST

## Seat Lock
- `seatIds` must not be empty
- all seat ids must belong to same event
- lock request should be atomic
- requested seats must be `AVAILABLE`
- `bookingId` is mandatory
- lock TTL should respect configured max/min limits

## Seat Confirm
- all seats must be locked by same booking
- expired lock should not be confirmed unless recovery/override rule exists

## Seat Release
- release should be idempotent
- releasing already available seats may return success with `releasedCount = 0` or no-op semantics
- booked-seat cancellation is handled through the same release endpoint

---

# Idempotency Expectations

| API / Contract | Idempotent | Notes |
|---|---|---|
| Kafka `inventory-init.v1` | yes | Must protect duplicate inventory creation |
| POST /internal/seats/lock | yes | Same booking + same seats + same key should replay safely |
| POST /internal/seats/confirm | yes | Duplicate payment callbacks should not double-book |
| POST /internal/seats/release | yes | Safe to retry for both lock release and booked-seat cancellation |

Recommended idempotency storage:
- Redis for short TTL keys
- PostgreSQL table for durable idempotency records on critical state transitions

---

# Async Kafka Contracts

These are not REST APIs, but they are part of the service contract.

## 1. InventoryInitRequested
Consumed by Seat Allocation Service. Published by Event Management Service.

### Topic
```text
inventory-init.v1
```

### Payload
```json
{
  "requestId": "req_init_001",
  "eventId": "9c9a7b0f-bf09-4f91-9235-4a4bbf34f97b",
  "venueId": "6f63c2bb-6995-4be3-a472-9cf2343a70ef",
  "inventoryType": "RESERVED",
  "seatMapVersion": 3,
  "triggeredAt": "2026-03-16T15:25:00Z"
}
```

### Processing Notes
- this is the live inventory initialization entry point
- no REST inventory-init endpoint is currently exposed by this service
- idempotency is enforced using `requestId` together with the inventory payload

---

## 2. InventoryInitialized
Published by Seat Allocation Service after successful inventory creation.

### Topic
```text
inventory-published.v1
```

### Payload
```json
{
  "requestId": "req_init_001",
  "eventId": "9c9a7b0f-bf09-4f91-9235-4a4bbf34f97b",
  "status": "SUCCESS",
  "totalSeats": 500,
  "seatMapVersion": 3,
  "processedAt": "2026-03-16T15:30:00Z"
}
```

### Failure Payload Example
```json
{
  "requestId": "req_init_001",
  "eventId": "9c9a7b0f-bf09-4f91-9235-4a4bbf34f97b",
  "status": "FAILED",
  "errorCode": "SEAT_MAP_NOT_FOUND",
  "message": "Seat map metadata missing",
  "processedAt": "2026-03-16T15:30:00Z"
}
```

---

# Security Considerations

- Internal mutation APIs should not be directly exposed to clients
- Protect write APIs with service authentication and authorization
- Enforce idempotency on all mutation endpoints
- Validate caller ownership for booking-related operations
- Add audit logs for block/unblock/manual overrides
- Mask sensitive data in logs
- Consider rate limiting on public query APIs

---

# Suggested HTTP Status Usage

| Operation | Success Status |
|---|---|
| Seat query | 200 OK |
| Lock seats | 200 OK |
| Confirm seats | 200 OK |
| Release seats | 200 OK |

---

# Open Design Decisions

These should be finalized while implementing:
1. Whether seat lock is fully DB-driven or DB + Redis hybrid
2. Whether availability reads come from PostgreSQL, Redis, or pre-aggregated cache
3. Whether partial seat lock success is allowed or request must remain atomic
4. Whether cancel moves seats to AVAILABLE immediately or through refund settlement workflow
5. Whether general-admission inventory lives in same schema or separate capacity table

---

# Future Considerations
1. Block and Unblock seats api.
2. SeatLockExpired background event for observability/workflow sync.

## Author

Abhinav Mantri
