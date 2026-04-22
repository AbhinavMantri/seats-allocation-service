package com.example.seats_allocation_service.exceptions;

public class EventInventoryAlreadyExistsException extends RuntimeException {
    public EventInventoryAlreadyExistsException(String message) {
        super(message);
    }
}
