package com.example.seats_allocation_service.dtos;

import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Data
public class InventoryInitRequestedEvent {
    private String eventType;
    private UUID eventId;
    private UUID venueId;
    private UUID organiserId;
    private String organiserEmail;
    private String title;
    private String category;
    private Instant startsAt;
    private Instant endsAt;
    private Instant publishedAt;
    private List<SectionPrice> sectionPrices;
    private List<SeatInventory> seats;

    public String getRequestId() {
        return eventId == null ? null : eventId.toString();
    }

    public Integer getSeatMapVersion() {
        return null;
    }

    public String getInventoryType() {
        return eventType;
    }

    @Data
    public static class SectionPrice {
        private UUID sectionId;
        private String sectionName;
        private Integer sortOrder;
        private Integer priceCents;
        private String currency;
    }

    @Data
    public static class SeatInventory {
        private UUID eventSeatId;
        private UUID venueSeatId;
        private UUID sectionId;
        private String seatCode;
        private String rowLabel;
        private Integer seatNumber;
        private Integer priceCents;
        private String currency;
    }
}
