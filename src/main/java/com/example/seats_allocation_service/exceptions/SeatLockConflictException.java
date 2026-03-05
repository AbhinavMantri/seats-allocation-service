package com.example.seats_allocation_service.exceptions;

public class SeatLockConflictException extends RuntimeException {
    public SeatLockConflictException(String message) {
        super(message);
    }
}
