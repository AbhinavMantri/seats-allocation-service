# Seat Allocation Service

## Overview

The **Seat Allocation Service** is responsible for managing seat
inventory for events. It initializes seat inventory, allocates seats
during ticket booking, releases seats on cancellation, and ensures
consistency across the ticketing system.

This service works closely with: - **Event Management Service** (creates
events and triggers inventory initialization) - **Booking/Ticket
Service** (requests seat allocation) - **Payment Service** (finalizes
allocation after successful payment)

Communication between services can be **synchronous for critical
operations** and **asynchronous via Kafka for background workflows**.

------------------------------------------------------------------------

## Responsibilities

-   Initialize seat inventory for a newly created event
-   Allocate seats during booking
-   Lock seats temporarily during payment
-   Release seats if payment fails or booking expires
-   Provide seat availability information
-   Maintain seat status consistency

------------------------------------------------------------------------

## Architecture

    Event Service
         |
         |  (InventoryInitRequested - Kafka)
         v
    Seat Allocation Service
         |
         |---- PostgreSQL (Seat inventory)
         |
         |---- Redis (Seat locks / temporary reservations)
         |
         v
    Booking Service

------------------------------------------------------------------------

## Technology Stack

-   **Language:** Java
-   **Framework:** Spring Boot
-   **Database:** PostgreSQL
-   **Cache:** Redis
-   **Messaging:** Kafka
-   **Containerization:** Docker
-   **Build Tool:** Maven

------------------------------------------------------------------------

## Seat States

  State       Description
  ----------- ---------------------------------------------
  AVAILABLE   Seat is free for booking
  LOCKED      Temporarily reserved during booking/payment
  BOOKED      Successfully purchased
  RELEASED    Seat released after cancellation or timeout

------------------------------------------------------------------------

## Core APIs

### 1. Initialize Inventory

Inventory initialization is currently triggered asynchronously by Kafka.
There is no REST `POST /internal/inventory/init` endpoint in the current
implementation.

------------------------------------------------------------------------

### 2. Get Seat Availability

    GET /events/{eventId}/seats

Response:

``` json
{
  "eventId": "evt_123",
  "availableSeats": 450,
  "lockedSeats": 20,
  "bookedSeats": 30
}
```

------------------------------------------------------------------------

### 3. Lock Seats

Temporarily locks seats during checkout.

    POST /internal/seats/lock

``` json
{
  "eventId": "evt_123",
  "seatIds": ["A1","A2","A3"],
  "bookingId": "bk_987"
}
```

------------------------------------------------------------------------

### 4. Confirm Seats

Marks seats as booked after payment success.

    POST /internal/seats/confirm

------------------------------------------------------------------------

### 5. Release Seats

Releases locked seats if payment fails or booking expires.

    POST /internal/seats/release

------------------------------------------------------------------------

### 6. Get Lock Details

Fetches the active lock for a booking, including seat-level pricing and the
aggregated total amount.

    GET /internal/locks?bookingId={bookingId}

Response:

``` json
{
  "status": "SUCCESS",
  "message": "Lock details fetched successfully",
  "result": {
    "bookingId": "ce10f1d8-f7c5-4e0a-a6d5-6c40b3376c0f",
    "eventId": "9c9a7b0f-bf09-4f91-9235-4a4bbf34f97b",
    "seats": [
      {
        "eventSeatId": "5f84b7a0-2d91-4db6-bd54-43b2c2a4337f",
        "sectionId": "6d4f7126-55f3-4a96-b4c0-c592b6eefb8d",
        "priceCents": 2200
      },
      {
        "eventSeatId": "b3cd59be-f47c-4513-91f2-5ef97afac5b9",
        "sectionId": "6d4f7126-55f3-4a96-b4c0-c592b6eefb8d",
        "priceCents": 1800
      }
    ],
    "totalAmountMinor": 4000,
    "currency": "USD",
    "lockExpiresAt": "2026-03-16T15:50:00Z",
    "status": "LOCKED"
  }
}
```

------------------------------------------------------------------------

## Kafka Events

### InventoryInitRequested

Triggered by **Event Service** after event creation.

Topic:

    inventory-init.v1

Example payload:

``` json
{
  "eventType": "EVENT_PUBLISHED",
  "eventId": "9c9a7b0f-bf09-4f91-9235-4a4bbf34f97b",
  "venueId": "6f63c2bb-6995-4be3-a472-9cf2343a70ef",
  "organiserId": "e8f9d5f4-4b52-4d4f-8b9c-c58aaf2e3b58",
  "organiserEmail": "ops@example.com",
  "title": "Spring Music Fest",
  "category": "MUSIC",
  "startsAt": "2026-04-20T18:30:00Z",
  "endsAt": "2026-04-20T22:00:00Z",
  "publishedAt": "2026-04-12T12:40:00Z",
  "sectionPrices": [
    {
      "sectionId": "a9d9ad1a-d9ef-4f19-b79e-dbd0fcaef652",
      "sectionName": "VIP",
      "sortOrder": 1,
      "priceCents": 2500,
      "currency": "INR"
    }
  ],
  "seats": [
    {
      "eventSeatId": "88a6a952-17e9-4748-a56a-47f231e82e55",
      "venueSeatId": "a2b35f4d-a31a-41db-ae0b-d0b0217bfe9d",
      "sectionId": "a9d9ad1a-d9ef-4f19-b79e-dbd0fcaef652",
      "seatCode": "VIP-R01-S01",
      "rowLabel": "R01",
      "seatNumber": 1,
      "priceCents": 2500,
      "currency": "INR"
    }
  ]
}
```

Notes:
- `requestId` is derived from `eventId` in the current consumer.
- Currency is resolved from `seats[].currency`, then `sectionPrices[].currency`,
  and falls back to the configured default if missing.
- Seat inventory creation uses the `seats` array and maps
  `venueSeatId`, `sectionId`, and `priceCents` into the service's
  pricing model.

------------------------------------------------------------------------

### InventoryInitialized

Published after inventory creation.

Topic:

    inventory-published.v1

Example payload:

``` json
{
  "requestId": "9c9a7b0f-bf09-4f91-9235-4a4bbf34f97b",
  "eventId": "9c9a7b0f-bf09-4f91-9235-4a4bbf34f97b",
  "status": "SUCCESS",
  "totalSeats": 1,
  "seatMapVersion": null,
  "processedAt": "2026-04-12T12:40:05Z"
}
```

------------------------------------------------------------------------

## Database Schema

### seats table

  Column         Type        Description
  -------------- ----------- --------------------
  id             UUID        Primary key
  event_id       VARCHAR     Event identifier
  seat_number    VARCHAR     Seat identifier
  status         VARCHAR     Seat state
  locked_until   TIMESTAMP   Lock expiry
  booking_id     VARCHAR     Associated booking

------------------------------------------------------------------------

## Running Locally

### Prerequisites

-   Docker
-   Java 17
-   Maven
-   Kafka
-   PostgreSQL
-   Redis

------------------------------------------------------------------------

### Start dependencies

Example using Docker:

    docker compose up -d

------------------------------------------------------------------------

### Run the service

    mvn spring-boot:run

------------------------------------------------------------------------

## Configuration

Example `application.yml`

    spring:
      datasource:
        url: jdbc:postgresql://localhost:5432/allocation_db
        username: postgres
        password: postgres

      redis:
        host: localhost
        port: 6379

      kafka:
        bootstrap-servers: localhost:9092

------------------------------------------------------------------------

## Future Improvements

-   Seat selection optimization
-   Distributed seat locking strategy
-   Dead-letter queue for failed events
-   Event sourcing for seat state history
-   High availability using Kafka partitions

------------------------------------------------------------------------

## Author

Abhinav Mantri
