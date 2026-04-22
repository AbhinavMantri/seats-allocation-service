package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.config.EventSeatsJwtAuthenticationFilter;
import com.example.seats_allocation_service.dtos.SeatAvailabilityResponse;
import com.example.seats_allocation_service.exceptions.EventNotFoundException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
import com.example.seats_allocation_service.models.EventSeat;
import com.example.seats_allocation_service.service.EventSeatService;
import com.example.seats_allocation_service.service.JWTService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class EventSeatsControllerApiTest {
    private static final String JWT_SECRET = "dev-secret-change-me";
    private static final String JWT_ISSUER = "user-service";

    @Mock
    private EventSeatService eventSeatService;

    @InjectMocks
    private EventSeatsController eventSeatsController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(eventSeatsController)
                .addFilters(new EventSeatsJwtAuthenticationFilter(new JWTService(JWT_SECRET, JWT_ISSUER, objectMapper)))
                .build();
    }

    @Test
    void getSeatsApi_whenSuccessful_returnsSeatPayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        EventSeat seat = new EventSeat();
        seat.setId(seatId);
        seat.setEventId(eventId);
        seat.setVenueSeatId(UUID.randomUUID());
        seat.setSectionId(UUID.randomUUID());
        seat.setPriceCents(2200);
        seat.setStatus(EventSeat.SeatStatus.AVAILABLE);
        when(eventSeatService.getSeats(eventId)).thenReturn(List.of(seat));

        mockMvc.perform(get("/events/{eventId}/seats", eventId)
                        .header(EventSeatsJwtAuthenticationFilter.HEADER_AUTHORIZATION, "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Seat availability fetched successfully"))
                .andExpect(jsonPath("$.seats[0].id").value(seatId.toString()))
                .andExpect(jsonPath("$.seats[0].eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.seats[0].priceCents").value(2200));
    }

    @Test
    void lockSeatsApi_whenSuccessful_returnsSuccessPayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        doNothing().when(eventSeatService).lockSeats(eq(eventId), eq("idem-123"), eq(userId), eq(List.of(seatId)));

        String requestJson = """
                {
                  "userId": "%s",
                  "idempotencyKey": "idem-123",
                  "seatIds": ["%s"]
                }
                """.formatted(userId, seatId);

        mockMvc.perform(post("/events/{eventId}/locks", eventId)
                        .header(EventSeatsJwtAuthenticationFilter.HEADER_AUTHORIZATION, "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Seats locked successfully"));
    }

    @Test
    void lockSeatsApi_whenConflictOccurs_returnsConflictPayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        doThrow(new SeatLockConflictException("seat conflict"))
                .when(eventSeatService)
                .lockSeats(eq(eventId), eq("idem-123"), eq(userId), eq(List.of(seatId)));

        String requestJson = """
                {
                  "userId": "%s",
                  "idempotencyKey": "idem-123",
                  "seatIds": ["%s"]
                }
                """.formatted(userId, seatId);

        mockMvc.perform(post("/events/{eventId}/locks", eventId)
                        .header(EventSeatsJwtAuthenticationFilter.HEADER_AUTHORIZATION, "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("seat conflict"));
    }

    @Test
    void releaseLocksApi_whenSuccessful_returnsReleasedCountPayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(eventSeatService.releaseLocks(eventId, userId, List.of(seatId))).thenReturn(1);

        String requestJson = """
                {
                  "userId": "%s",
                  "seatIds": ["%s"]
                }
                """.formatted(userId, seatId);

        mockMvc.perform(post("/events/{eventId}/locks/release", eventId)
                        .header(EventSeatsJwtAuthenticationFilter.HEADER_AUTHORIZATION, "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Released 1 seat lock(s)"));
    }

    @Test
    void getAvailabilitySummaryApi_whenSuccessful_returnsSummaryPayload() throws Exception {
        UUID eventId = UUID.randomUUID();
        SeatAvailabilityResponse response = new SeatAvailabilityResponse();
        response.setTotalSeats(120);
        response.setAvailableSeats(90);
        response.setLockedSeats(15);
        when(eventSeatService.getAvailabilitySummary(eventId)).thenReturn(response);

        mockMvc.perform(get("/events/{eventId}/seats/availability", eventId)
                        .header(EventSeatsJwtAuthenticationFilter.HEADER_AUTHORIZATION, "Bearer " + jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Seat availability summary fetched successfully"))
                .andExpect(jsonPath("$.totalSeats").value(120))
                .andExpect(jsonPath("$.availableSeats").value(90))
                .andExpect(jsonPath("$.lockedSeats").value(15));
    }

    @Test
    void getAvailabilitySummaryApi_whenEventDoesNotExist_returnsNotFound() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(eventSeatService.getAvailabilitySummary(eventId))
                .thenThrow(new EventNotFoundException("missing event"));

        mockMvc.perform(get("/events/{eventId}/seats/availability", eventId)
                        .header(EventSeatsJwtAuthenticationFilter.HEADER_AUTHORIZATION, "Bearer " + jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("missing event"));
    }

    @Test
    void lockSeatsApi_whenRequestIsInvalid_returnsBadRequest() throws Exception {
        UUID eventId = UUID.randomUUID();

        String requestJson = """
                {
                  "idempotencyKey": "",
                  "seatIds": []
                }
                """;

        mockMvc.perform(post("/events/{eventId}/locks", eventId)
                        .header(EventSeatsJwtAuthenticationFilter.HEADER_AUTHORIZATION, "Bearer " + jwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSeatsApi_whenAuthorizationHeaderIsMissing_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/events/{eventId}/seats", UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Unauthorized: valid JWT token is required"));
    }

    @Test
    void getSeatsApi_whenTokenIsInvalid_returnsUnauthorized() throws Exception {
        mockMvc.perform(get("/events/{eventId}/seats", UUID.randomUUID())
                        .header(EventSeatsJwtAuthenticationFilter.HEADER_AUTHORIZATION, "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Unauthorized: valid JWT token is required"));
    }

    private String jwt() throws Exception {
        String headerJson = objectMapper.writeValueAsString(Map.of("alg", "HS256", "typ", "JWT"));
        String payloadJson = objectMapper.writeValueAsString(Map.of(
                "sub", UUID.randomUUID().toString(),
                "iss", JWT_ISSUER,
                "exp", (System.currentTimeMillis() / 1000L) + 3600
        ));
        String header = base64Url(headerJson);
        String payload = base64Url(payloadJson);
        String unsignedToken = header + "." + payload;
        String signature = base64Url(hmacSha256(unsignedToken, JWT_SECRET));
        return unsignedToken + "." + signature;
    }

    private String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] hmacSha256(String data, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }
}