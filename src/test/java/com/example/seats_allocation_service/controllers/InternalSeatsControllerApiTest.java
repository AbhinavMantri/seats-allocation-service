package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.config.InternalApiAccessInterceptor;
import com.example.seats_allocation_service.dtos.ReleaseReason;
import com.example.seats_allocation_service.dtos.ReleaseSeatsResult;
import com.example.seats_allocation_service.dtos.SeatsConfirmation;
import com.example.seats_allocation_service.exceptions.IdempotencyConflictException;
import com.example.seats_allocation_service.exceptions.SeatLockConflictException;
import com.example.seats_allocation_service.service.InternalSeatsService;
import com.example.seats_allocation_service.service.JWTService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalSeatsControllerApiTest {
    private static final String JWT_SECRET = "dev-secret-change-me";
    private static final String JWT_ISSUER = "user-service";

    @Mock
    private InternalSeatsService internalSeatsService;

    @InjectMocks
    private InternalSeatsController internalSeatsController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(internalSeatsController)
                .addInterceptors(new InternalApiAccessInterceptor(
                        Map.of("inventory-service", "svc-token"),
                        new JWTService(JWT_SECRET, JWT_ISSUER, objectMapper)
                ))
                .build();
    }

    @Test
    void confirmSeatsApi_whenAuthorizedAndSuccessful_returnsPayload() throws Exception {
        String idempotencyKey = "idem-confirm";
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Instant confirmedAt = Instant.parse("2026-03-21T12:00:00Z");
        when(internalSeatsService.confirmSeats(idempotencyKey, eventId, bookingId, paymentId, List.of(seatId), confirmedAt))
                .thenReturn(SeatsConfirmation.builder()
                        .eventId(eventId)
                        .bookingId(bookingId)
                        .paymentId(paymentId)
                        .seatIds(List.of(seatId))
                        .bookedCount(1)
                        .confirmedAt(confirmedAt.toString())
                        .build());

        String requestJson = """
                {
                  "eventId": "%s",
                  "bookingId": "%s",
                  "paymentId": "%s",
                  "seatIds": ["%s"],
                  "confirmedAt": "%s"
                }
                """.formatted(eventId, bookingId, paymentId, seatId, confirmedAt);

        mockMvc.perform(post("/internal/seats/confirm")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_NAME, "inventory-service")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_TOKEN, "svc-token")
                        .header(InternalApiAccessInterceptor.HEADER_AUTHORIZATION, "Bearer " + jwtWithRole("ADMIN"))
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Confirmed 1 seat(s)"))
                .andExpect(jsonPath("$.seatConfirmation.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.seatConfirmation.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.seatConfirmation.bookedCount").value(1));
    }

    @Test
    void releaseSeatsApi_whenAuthorizedAndSuccessful_returnsPayload() throws Exception {
        String idempotencyKey = "idem-release";
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        ReleaseSeatsResult result = new ReleaseSeatsResult();
        result.setEventId(eventId);
        result.setBookingId(bookingId);
        result.setSeatIds(List.of(seatId));
        result.setReleasedCount(1);
        when(internalSeatsService.releaseSeats(
                idempotencyKey,
                eventId.toString(),
                bookingId.toString(),
                List.of(seatId.toString()),
                ReleaseReason.BOOKING_CANCELLED
        )).thenReturn(result);

        String requestJson = """
                {
                  "eventId": "%s",
                  "bookingId": "%s",
                  "seatIds": ["%s"],
                  "reason": "BOOKING_CANCELLED"
                }
                """.formatted(eventId, bookingId, seatId);

        mockMvc.perform(post("/internal/seats/release")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_NAME, "inventory-service")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_TOKEN, "svc-token")
                        .header(InternalApiAccessInterceptor.HEADER_AUTHORIZATION, "Bearer " + jwtWithRole("ADMIN"))
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Released 1 seat(s)"))
                .andExpect(jsonPath("$.result.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.result.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.result.releasedCount").value(1));
    }

    @Test
    void releaseSeatsApi_whenServiceRejectsRequest_returnsConflict() throws Exception {
        String idempotencyKey = "idem-release";
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(internalSeatsService.releaseSeats(
                idempotencyKey,
                eventId.toString(),
                bookingId.toString(),
                List.of(seatId.toString()),
                ReleaseReason.BOOKING_CANCELLED
        )).thenThrow(new SeatLockConflictException("seat not releasable"));

        String requestJson = """
                {
                  "eventId": "%s",
                  "bookingId": "%s",
                  "seatIds": ["%s"],
                  "reason": "BOOKING_CANCELLED"
                }
                """.formatted(eventId, bookingId, seatId);

        mockMvc.perform(post("/internal/seats/release")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_NAME, "inventory-service")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_TOKEN, "svc-token")
                        .header(InternalApiAccessInterceptor.HEADER_AUTHORIZATION, "Bearer " + jwtWithRole("ADMIN"))
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("seat not releasable"));
    }

    @Test
    void confirmSeatsApi_whenIdempotencyKeyConflicts_returnsConflict() throws Exception {
        String idempotencyKey = "idem-confirm";
        UUID eventId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        Instant confirmedAt = Instant.parse("2026-03-21T12:00:00Z");
        when(internalSeatsService.confirmSeats(idempotencyKey, eventId, bookingId, paymentId, List.of(seatId), confirmedAt))
                .thenThrow(new IdempotencyConflictException("idempotency key conflict"));

        String requestJson = """
                {
                  "eventId": "%s",
                  "bookingId": "%s",
                  "paymentId": "%s",
                  "seatIds": ["%s"],
                  "confirmedAt": "%s"
                }
                """.formatted(eventId, bookingId, paymentId, seatId, confirmedAt);

        mockMvc.perform(post("/internal/seats/confirm")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_NAME, "inventory-service")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_TOKEN, "svc-token")
                        .header(InternalApiAccessInterceptor.HEADER_AUTHORIZATION, "Bearer " + jwtWithRole("ADMIN"))
                        .header("X-Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("idempotency key conflict"));
    }

    @Test
    void confirmSeatsApi_whenInternalAccessHeadersAreMissing_returnsForbidden() throws Exception {
        String requestJson = """
                {
                  "eventId": "%s",
                  "bookingId": "%s",
                  "paymentId": "%s",
                  "seatIds": ["%s"],
                  "confirmedAt": "%s"
                }
                """.formatted(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.parse("2026-03-21T12:00:00Z")
        );

        mockMvc.perform(post("/internal/seats/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Forbidden: internal endpoint access denied"));
    }

    private String jwtWithRole(String role) throws Exception {
        String headerJson = objectMapper.writeValueAsString(Map.of("alg", "HS256", "typ", "JWT"));
        String payloadJson = objectMapper.writeValueAsString(Map.of(
                "role", role,
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
