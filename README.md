# Seats Allocation Service

Spring Boot microservice for event-level seat inventory, availability reads, lock-before-pay checkout protection, seat confirmation, release handling, and Kafka-based inventory initialization.

This repository is part of the Ticketmaster-style backend platform. It owns the consistency boundary around event seats: which seats exist for an event, which seats are currently available, which seats are temporarily locked for checkout, and which seats are booked after payment succeeds.

## Where It Fits

```text
event-management-service
      |
      | inventory-init.v1
      v
Kafka
      |
      v
seats-allocation-service
      |
      | lock / confirm / release / lock details
      v
booking-service + payment flow
```

Kafka is used for inventory initialization only:

`event-management-service -> Kafka -> seats-allocation-service`

Checkout-time locking, confirmation, release, and lock lookup are synchronous service APIs because those paths need immediate consistency feedback.

## What It Does Today

- Consumes `inventory-init.v1` events from Kafka.
- Creates one `event_inventory_context` row per event.
- Creates event-level seat inventory in `event_seats`.
- Publishes `inventory-published.v1` after successful inventory initialization.
- Exposes seat map and availability summary APIs.
- Locks seats for checkout with a 10-minute TTL.
- Uses PostgreSQL pessimistic row locks when locking, confirming, or releasing seats.
- Uses idempotency records for lock, confirm, release, and inventory initialization operations.
- Uses Redis as a short-lived cache for seat maps and idempotent response replay.
- Confirms locked seats into `BOOKED` after payment/booking success.
- Releases locked or booked seats for failure, cancellation, timeout, or recovery flows.
- Exposes lock details for downstream payment amount calculation.
- Logs important state transitions with latency and request context.

## Core Components

| Component | Responsibility |
| --- | --- |
| `InternalEventInventoryController` | Kafka listener for inventory initialization and publisher for inventory initialized events |
| `EventInventoryService` | Event inventory context creation, inventory-init idempotency, currency resolution |
| `EventSeatService` | Seat map reads, availability summary, lock and user-release operations |
| `InternalSeatsService` | Internal confirm/release flows with idempotent replay |
| `LockService` | Active lock lookup for booking/payment workflows |
| `EventSeatRepository` | Seat persistence and pessimistic row locks |
| `AllocationIdempotencyRepository` | Durable idempotency records |
| `InternalApiAccessInterceptor` | Service-token/JWT role checks for internal APIs |

## Seat State Model

Supported seat states:

- `AVAILABLE`: seat can be selected and locked.
- `LOCKED`: seat is temporarily reserved for a checkout owner until `lockExpiresAt`.
- `BOOKED`: seat has been confirmed for a booking.

The current schema intentionally keeps the state model small. Release is handled as a transition back to `AVAILABLE`, not as a stored `RELEASED` state.

## Inventory Initialization

`event-management-service` publishes `inventory-init.v1` when event inventory should be created.

The service consumes that event, resolves currency, creates the event inventory context, creates event seat rows, and publishes `inventory-published.v1`.

Important behavior:

- `requestId` is used as the idempotency key for inventory initialization.
- A repeated event with the same payload is replayed safely.
- A repeated event with the same key but different payload is rejected as an idempotency conflict.
- Redis is used as a fast replay cache; PostgreSQL remains the durable idempotency source.

## Lock-Before-Pay Flow

1. Booking or checkout calls the lock API with event ID, seat IDs, user/booking owner, and idempotency key.
2. The service de-duplicates seat IDs and calculates a payload hash.
3. Existing idempotency records are checked before mutating state.
4. Requested seat rows are loaded with `PESSIMISTIC_WRITE`.
5. If any requested seat is booked or actively locked by another owner, the request fails.
6. If all seats are valid, they move to `LOCKED` with `lockedBy` and `lockExpiresAt`.
7. Payment can use lock details to calculate amount/currency from the locked seats.
8. After payment success, internal confirmation moves the seats to `BOOKED`.
9. Failed, cancelled, or recovery flows release seats back to `AVAILABLE`.

This design keeps double-booking protection close to the seat inventory table rather than relying on payment or booking services to infer seat state.

## API Surface

Configured base path:

```text
/seats-allocation-service/v1
```

Primary endpoints:

| Endpoint | Purpose |
| --- | --- |
| `GET /events/{eventId}/seats` | Fetch event seat map |
| `GET /events/{eventId}/seats/availability` | Fetch aggregate availability counts |
| `POST /events/{eventId}/locks` | Public/protected lock operation |
| `POST /events/{eventId}/locks/release` | Public/protected user lock release |
| `POST /internal/seats/{eventId}/locks` | Internal lock operation |
| `POST /internal/seats/confirm` | Confirm locked seats after payment success |
| `POST /internal/seats/release` | Release seats for failure/cancellation/recovery |
| `POST /internal/seats/{eventId}/locks/release` | Internal lock release by user |
| `GET /internal/locks?bookingId={bookingId}` | Fetch active lock details and amount/currency |

Detailed request/response examples are in [api.md](api.md).

## Reliability And Consistency Choices

- **PostgreSQL is authoritative:** seat state is stored in `event_seats`.
- **Pessimistic row locking:** lock/confirm/release operations use `SELECT ... FOR UPDATE` semantics through JPA pessimistic locks.
- **Lock TTL:** locks expire after 10 minutes and are treated as available by availability reads when expired.
- **Idempotency:** lock, confirm, release, and inventory init flows store payload hashes and response payloads.
- **Conflict detection:** same idempotency key with different payload is rejected.
- **Redis as acceleration, not authority:** Redis is used for response replay and seat-map caching; durable state stays in PostgreSQL.
- **Kafka for background inventory propagation:** inventory initialization is asynchronous because it is not on the checkout critical path.
- **Synchronous checkout path:** seat locking and confirmation remain synchronous because callers need immediate consistency results.

## Data Model

Core tables:

- `event_inventory_context`
- `event_seats`
- `allocation_idempotency`

Schema: [scripts/db.sql](scripts/db.sql)

Important indexes:

- event + section lookup for seat map reads
- event + status lookup for availability reads
- lock expiry lookup for cleanup/recovery
- locked-by lookup for lock detail and release flows
- idempotency lookup by operation/resource/key

## Authentication

Read and public lock APIs require a valid JWT through `EventSeatsJwtAuthenticationFilter`.

Internal APIs require trusted service headers:

```http
X-Service-Name: <service-name>
X-Service-Token: <configured-token>
```

Some internal access paths also validate JWT roles and allow only `ADMIN` or `ORGANISER` claims.

## Configuration

Primary settings:

| Property | Purpose |
| --- | --- |
| `api.prefix` | Servlet context path |
| `spring.datasource.url` | PostgreSQL connection URL |
| `spring.datasource.username` | Database username |
| `spring.datasource.password` | Database password |
| `spring.kafka.bootstrap-servers` | Kafka broker list |
| `app.kafka.topics.inventory-init-request` | Inventory init input topic |
| `app.kafka.topics.inventory-init-result` | Inventory init result topic |
| `app.inventory.default-currency` | Currency fallback for inventory events |
| `internal.api.service-tokens.*` | Trusted service tokens |

## Running Locally

1. Start PostgreSQL, Redis, and Kafka.
2. Create/apply the `allocation_db` schema from [scripts/db.sql](scripts/db.sql).
3. Configure datasource, Kafka, Redis, JWT, and internal service-token properties.
4. Run:

```powershell
.\mvnw.cmd spring-boot:run
```

Run tests:

```powershell
.\mvnw.cmd test
```

## Current Limitations

- Expired locks are treated as available in reads, but there is no scheduled cleanup job in this repository yet.
- Kafka consumer retry/dead-letter handling is basic and should be hardened for production.
- Seat-map pagination/filtering is not implemented beyond returning the event seat list.
- Redis cache failure is tolerated, but cache metrics/alerts are not implemented.
- Lock TTL is currently fixed in code at 10 minutes.
- The service does not directly own payment or booking state; it only owns seat inventory state.

## Production Hardening Roadmap

- Add scheduled cleanup or async recovery for expired locks.
- Add Kafka retry topics and dead-letter topics for inventory initialization failures.
- Add outbox/event publication for seat lock/confirm/release state changes.
- Add explicit metrics for lock conflicts, expired locks, idempotency replays, and Kafka lag.
- Add pagination/section filtering for large venue seat maps.
- Move lock TTL to configuration.
- Add contract tests across event-management, booking, payment, and seats-allocation boundaries.
