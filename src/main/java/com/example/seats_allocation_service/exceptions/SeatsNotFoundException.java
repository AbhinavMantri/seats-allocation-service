package com.example.seats_allocation_service.exceptions;

public class SeatsNotFoundException extends RuntimeException {
    public SeatsNotFoundException(String message) {
        super(message);
    }
}
