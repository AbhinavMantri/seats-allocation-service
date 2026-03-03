-- Seat Allocation Service (allocation_db) - PostgreSQL DDL
-- Owns: event_inventory_context + event_seats (with lock fields)
-- Assumes pgcrypto for UUID generation.

BEGIN;

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ---------------------------------------------------------
-- 1) Event Inventory Context (one row per event)
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS event_inventory_context (
  event_id    UUID PRIMARY KEY,
  venue_id    UUID NOT NULL,
  currency    VARCHAR(3) NOT NULL,
  status      VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE, CANCELLED
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT chk_inventory_status CHECK (status IN ('ACTIVE','CANCELLED')),
  CONSTRAINT chk_currency_len CHECK (length(currency) = 3)
);

CREATE INDEX IF NOT EXISTS idx_inv_ctx_venue_id
  ON event_inventory_context(venue_id);

CREATE INDEX IF NOT EXISTS idx_inv_ctx_status
  ON event_inventory_context(status);

-- ---------------------------------------------------------
-- 2) Event Seats (authoritative seat inventory per event)
-- ---------------------------------------------------------
CREATE TABLE IF NOT EXISTS event_seats (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  event_id        UUID NOT NULL,
  venue_seat_id   UUID NOT NULL, -- from event-service; external reference
  section_id      UUID NOT NULL, -- from event-service; external reference

  price_cents     INT  NOT NULL CHECK (price_cents > 0),

  status          VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE', -- AVAILABLE, LOCKED, BOOKED

  -- Lock fields (used when status=LOCKED)
  locked_by       UUID NULL,           -- userId from JWT (external reference)
  lock_expires_at TIMESTAMPTZ NULL,

  -- Booking fields (optional but useful)
  booking_id      UUID NULL,           -- external booking reference, if you have booking-service
  booked_at       TIMESTAMPTZ NULL,

  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  CONSTRAINT fk_event_seats_event
    FOREIGN KEY (event_id) REFERENCES event_inventory_context(event_id)
    ON DELETE CASCADE,

  CONSTRAINT chk_event_seat_status CHECK (status IN ('AVAILABLE','LOCKED','BOOKED')),

  -- Enforce one inventory row per physical seat per event
  CONSTRAINT uq_event_seat_per_event UNIQUE (event_id, venue_seat_id),

  -- Basic sanity for lock fields (still enforce in service logic too)
  CONSTRAINT chk_lock_fields_consistency CHECK (
    (status = 'LOCKED' AND locked_by IS NOT NULL AND lock_expires_at IS NOT NULL)
    OR
    (status <> 'LOCKED' AND locked_by IS NULL AND lock_expires_at IS NULL)
  ),

  CONSTRAINT chk_book_fields_consistency CHECK (
    (status = 'BOOKED' AND booked_at IS NOT NULL)
    OR
    (status <> 'BOOKED' AND booked_at IS NULL)
  )
);

-- ---------------------------------------------------------
-- 3) Indexes for fast read/lock operations
-- ---------------------------------------------------------

-- Get seat map quickly by event (+ optional section)
CREATE INDEX IF NOT EXISTS idx_event_seats_event_section
  ON event_seats(event_id, section_id);

-- Availability queries by status
CREATE INDEX IF NOT EXISTS idx_event_seats_event_status
  ON event_seats(event_id, status);

-- Expired lock scans (if you do scheduled cleanup)
CREATE INDEX IF NOT EXISTS idx_event_seats_event_lock_expiry
  ON event_seats(event_id, lock_expires_at)
  WHERE status = 'LOCKED';

-- Find all locks for a user (release, debugging)
CREATE INDEX IF NOT EXISTS idx_event_seats_locked_by
  ON event_seats(locked_by)
  WHERE status = 'LOCKED';

-- ---------------------------------------------------------
-- 4) Optional: Idempotency for lock requests (recommended)
-- ---------------------------------------------------------
-- Helps avoid duplicate locks when client retries due to timeouts.
CREATE TABLE IF NOT EXISTS lock_idempotency (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  event_id           UUID NOT NULL,
  user_id            UUID NOT NULL,
  idempotency_key    VARCHAR(120) NOT NULL,
  seat_ids_hash      VARCHAR(128) NOT NULL, -- hash of requested seatIds list
  response_payload   JSONB NOT NULL,        -- cached response (lock expiry, seats)
  created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (event_id, user_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_lock_idempotency_user
  ON lock_idempotency(user_id, created_at DESC);

COMMIT;

