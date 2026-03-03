package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.models.EventSeat;
import com.example.seats_allocation_service.repository.EventSeatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/events/{eventId}")
@RequiredArgsConstructor
public class EventSeatsController {

    private final EventSeatRepository eventSeatRepository;

    @GetMapping("/seats")
    public ResponseEntity<List<EventSeat>> getSeats(@PathVariable UUID eventId) {
        // TODO: get seats for eventId, include caching layer (Redis) to cache results for 5 minutes
        return ResponseEntity.ok(eventSeatRepository.findByEventId(eventId));
    }

    @PostMapping("/locks")
    public ResponseEntity<ApiResponse> lockSeats(
            @PathVariable UUID eventId,
            @RequestBody LockSeatsRequest request
    ) {
        // TODO: implement lock seats logic with idempotency key handling, seat locking, and response caching in Redis
        return ResponseEntity.ok(new ApiResponse(
                "LOCK_REQUEST_ACCEPTED",
                "Lock endpoint is set up for event " + eventId
        ));
    }

    @PostMapping("/locks/release")
    public ResponseEntity<ApiResponse> releaseLocks(
            @PathVariable UUID eventId,
            @RequestBody ReleaseLocksRequest request
    ) {
        // TODO: implement release locks logic to release locked seats for the user
        return ResponseEntity.ok(new ApiResponse(
                "RELEASE_REQUEST_ACCEPTED",
                "Release endpoint is set up for event " + eventId
        ));
    }

    public record LockSeatsRequest(UUID userId, String idempotencyKey, List<UUID> seatIds) {
    }

    public record ReleaseLocksRequest(UUID userId, List<UUID> seatIds) {
    }

    public record ApiResponse(String code, String message) {
    }
}
