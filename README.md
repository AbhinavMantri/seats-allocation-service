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

Creates seat inventory for an event.

    POST /internal/inventory/init

Example request:

``` json
{
  "eventId": "evt_123",
  "venueId": "ven_456",
  "totalSeats": 500
}
```

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

## Kafka Events

### InventoryInitRequested

Triggered by **Event Service** after event creation.

Topic:

    inventory-init.v1

Example payload:

``` json
{
  "eventId": "evt_123",
  "venueId": "ven_456",
  "totalSeats": 500,
  "requestId": "req_001"
}
```

------------------------------------------------------------------------

### InventoryInitialized

Published after inventory creation.

Topic:

    inventory-published.v1

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
