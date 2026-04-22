package com.example.seats_allocation_service.dtos;

public enum ReleaseReason {
    PAYMENT_FAILED,
    BOOKING_EXPIRED,
    SYSTEM_RECOVERY,
    BOOKING_CANCELLED,
    ORDER_CANCELLED,
    PAYMENT_TIMEOUT,
    MANUAL_RELEASE,
    INVENTORY_ROLLBACK
}
