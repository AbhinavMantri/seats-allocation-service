package com.example.seats_allocation_service.exceptions;

import com.example.seats_allocation_service.dtos.LockSeatResponse;
import com.example.seats_allocation_service.dtos.common.ResponseStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(SeatsNotFoundException.class)
    public ResponseEntity<LockSeatResponse> handleSeatsNotFound(SeatsNotFoundException ex) {
        LockSeatResponse response = new LockSeatResponse();
        response.setStatus(ResponseStatus.FAILURE);
        response.setMessage(ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(SeatLockConflictException.class)
    public ResponseEntity<LockSeatResponse> handleSeatLockConflict(SeatLockConflictException ex) {
        LockSeatResponse response = new LockSeatResponse();
        response.setStatus(ResponseStatus.FAILURE);
        response.setMessage(ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
    }
}
