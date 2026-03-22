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
- **Base URL (example):**
  ```text
  /seats-allocation-service/v1
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
  "path": "/seats-allocation-service/v1/internal/seats/lock",
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

## 1. Initialize Event Inventory

Creates inventory records for all seats of an event. Typically called by **Event Management Service** after event creation or venue mapping resolution.

### Endpoint
```http
POST /seats-allocation-service/v1/internal/events/{eventId}/inventory/init
```

### Purpose
- Create seat records for a reserved-seat event
- Create section-level capacity records for general-admission events
- Mark inventory as initialized exactly once

### Request
```json
{
  "eventId": "evt_1001",
  "venueId": "ven_501",
  "inventoryType": "RESERVED",
  "requestId": "req_init_001",
  "seatMapVersion": 3,
  "sections": [
    {
      "sectionId": "SEC_A",
      "sectionName": "Platinum",
      "rows": [
        {
          "rowLabel": "A",
          "seats": ["A1", "A2", "A3", "A4"]
        },
        {
          "rowLabel": "B",
          "seats": ["B1", "B2", "B3", "B4"]
        }
      ]
    }
  ],
  "metadata": {
    "createdBy": "event-service"
  }
}
```

### Request Fields

| Field | Type | Required | Description |
|---|---|---|---|
| eventId | string | yes | Unique event identifier |
| venueId | string | yes | Venue identifier |
| inventoryType | string | yes | `RESERVED` or `GENERAL_ADMISSION` |
| requestId | string | yes | Caller-side request tracking id |
| seatMapVersion | integer | no | Version of seat layout |
| sections | array | yes for reserved | Section/row/seat layout |
| metadata | object | no | Extra audit metadata |

### Success Response
**HTTP 201 Created**
```json
{
  "eventId": "evt_1001",
  "inventoryStatus": "INITIALIZED",
  "inventoryType": "RESERVED",
  "totalSeats": 8,
  "seatMapVersion": 3,
  "initializedAt": "2026-03-16T15:30:00Z",
  "requestId": "req_init_001"
}
```

### Idempotency
- Same `X-Idempotency-Key` + same payload → return same successful response
- Same `X-Idempotency-Key` + different payload → `409 IDEMPOTENCY_CONFLICT`

---

## 2. Get Seat Availability Summary

Returns aggregated seat counts for an event.

### Endpoint
```http
GET /seats-allocation-service/v1/events/{eventId}/availability
```

### Path Params

| Param | Type | Required | Description |
|---|---|---|---|
| eventId | string | yes | Event identifier |

### Success Response
**HTTP 200 OK**
```json
{
  "eventId": "evt_1001",
  "inventoryType": "RESERVED",
  "totalSeats": 500,
  "availableSeats": 420,
  "lockedSeats": 30,
  "bookedSeats": 45,
  "blockedSeats": 5,
  "lastUpdatedAt": "2026-03-16T15:40:00Z"
}
```

---

## 3. Get Seat Map

Returns seat-level state for rendering a seat map UI.

### Endpoint
```http
GET /seats-allocation-service/v1/events/{eventId}/seats
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
  "eventId": "evt_1001",
  "seatMapVersion": 3,
  "sections": [
    {
      "sectionId": "SEC_A",
      "sectionName": "Platinum",
      "rows": [
        {
          "rowLabel": "A",
          "seats": [
            {
              "seatId": "A1",
              "status": "AVAILABLE",
              "priceZone": "P1"
            },
            {
              "seatId": "A2",
              "status": "LOCKED",
              "priceZone": "P1",
              "lockExpiresAt": "2026-03-16T15:45:00Z"
            }
          ]
        }
      ]
    }
  ]
}
```

---

## 4. Lock Seats

Temporarily reserves seats for a booking attempt.

### Endpoint
```http
POST /seats-allocation-service/v1/internal/seats/lock
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
  "eventId": "evt_1001",
  "bookingId": "bk_9001",
  "lockStatus": "LOCKED",
  "seatIds": ["A1", "A2", "A3"],
  "lockExpiresAt": "2026-03-16T15:50:00Z",
  "lockedCount": 3
}
```

### Failure Example
**HTTP 409 Conflict**
```json
{
  "timestamp": "2026-03-16T15:41:00Z",
  "path": "/seats-allocation-service/v1/internal/seats/lock",
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

## 5. Confirm Seats

Transitions locked seats to booked after successful payment.

### Endpoint
```http
POST /seats-allocation-service/v1/internal/seats/confirm
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
  "eventId": "evt_1001",
  "bookingId": "bk_9001",
  "paymentId": "pay_441",
  "seatIds": ["A1", "A2", "A3"],
  "bookedCount": 3,
  "confirmedAt": "2026-03-16T15:44:10Z"
}
```

---

## 6. Release Seats

Releases previously locked seats due to payment failure, user timeout, or cart expiration.

### Endpoint
```http
POST /seats-allocation-service/v1/internal/seats/release
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
- `USER_CANCELLED`
- `SYSTEM_RECOVERY`

### Success Response
```json
{
  "eventId": "evt_1001",
  "bookingId": "bk_9001",
  "releasedCount": 3,
  "seatIds": ["A1", "A2", "A3"],
  "status": "RELEASED"
}
```

---

## 7. Cancel Booked Seats

Used during order cancellation or refund flow to release previously booked inventory back to sellable state, depending on business rules.

### Endpoint
```http
POST /seats-allocation-service/v1/internal/seats/cancel
```

### Request
```json
{
  "eventId": "evt_1001",
  "bookingId": "bk_9001",
  "orderId": "ord_331",
  "seatIds": ["A1", "A2"],
  "reason": "ORDER_CANCELLED"
}
```

### Success Response
```json
{
  "eventId": "evt_1001",
  "orderId": "ord_331",
  "cancelledCount": 2,
  "seatIds": ["A1", "A2"],
  "status": "AVAILABLE"
}
```

---

## 8. Get Lock Status by Booking

Returns currently locked seats for a booking/cart.

### Endpoint
```http
GET /seats-allocation-service/v1/internal/locks?bookingId={bookingId}
```

### Success Response
```json
{
  "bookingId": "bk_9001",
  "eventId": "evt_1001",
  "seatIds": ["A1", "A2", "A3"],
  "lockExpiresAt": "2026-03-16T15:50:00Z",
  "status": "LOCKED"
}
```

---

## 9. Health Check

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

---

# Idempotency Expectations

| API | Idempotent | Notes |
|---|---|---|
| POST /internal/inventory/init | yes | Must protect duplicate inventory creation |
| POST /internal/seats/lock | yes | Same booking + same seats + same key should replay safely |
| POST /internal/seats/confirm | yes | Duplicate payment callbacks should not double-book |
| POST /internal/seats/release | yes | Safe to retry |
| POST /internal/seats/cancel | yes | Safe during retry/reconciliation |

Recommended idempotency storage:
- Redis for short TTL keys
- PostgreSQL table for durable idempotency records on critical state transitions

---

# Async Kafka Contracts

These are not REST APIs, but they are part of the service contract.

## 1. InventoryInitRequested
Published by Event Management Service.

### Topic
```text
inventory-init.v1
```

### Payload
```json
{
  "requestId": "req_init_001",
  "eventId": "evt_1001",
  "venueId": "ven_501",
  "inventoryType": "RESERVED",
  "seatMapVersion": 3,
  "triggeredAt": "2026-03-16T15:25:00Z"
}
```

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
  "eventId": "evt_1001",
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
  "eventId": "evt_1001",
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
| Inventory init | 201 Created |
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
