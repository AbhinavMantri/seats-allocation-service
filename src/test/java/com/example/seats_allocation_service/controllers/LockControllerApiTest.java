package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.config.InternalApiAccessInterceptor;
import com.example.seats_allocation_service.dtos.LockDetail;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.service.JWTService;
import com.example.seats_allocation_service.service.LockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LockControllerApiTest {
    private static final String JWT_SECRET = "dev-secret-change-me";
    private static final String JWT_ISSUER = "user-service";

    @Mock
    private LockService lockService;

    @InjectMocks
    private LockController lockController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(lockController)
                .addInterceptors(new InternalApiAccessInterceptor(
                        Map.of("inventory-service", "svc-token"),
                        new JWTService(JWT_SECRET, JWT_ISSUER, objectMapper)
                ))
                .build();
    }

    @Test
    void getLockDetailsApi_whenAuthorizedAndLockExists_returnsPayload() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        when(lockService.getLockDetails(bookingId)).thenReturn(LockDetail.builder()
                .bookingId(bookingId)
                .eventId(eventId)
                .seatIds(List.of(seatId))
                .lockExpiresAt("2026-03-21T10:15:30Z")
                .status("LOCKED")
                .build());

        mockMvc.perform(get("/internal/locks")
                        .param("bookingId", bookingId.toString())
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_NAME, "inventory-service")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_TOKEN, "svc-token")
                        .header(InternalApiAccessInterceptor.HEADER_AUTHORIZATION, "Bearer " + jwtWithRole("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Lock details fetched successfully"))
                .andExpect(jsonPath("$.result.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.result.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.result.seatIds[0]").value(seatId.toString()))
                .andExpect(jsonPath("$.result.status").value("LOCKED"));
    }

    @Test
    void getLockDetailsApi_whenLockIsMissing_returnsNotFoundPayload() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(lockService.getLockDetails(bookingId))
                .thenThrow(new SeatsNotFoundException("No active lock found for bookingId: " + bookingId));

        mockMvc.perform(get("/internal/locks")
                        .param("bookingId", bookingId.toString())
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_NAME, "inventory-service")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_TOKEN, "svc-token")
                        .header(InternalApiAccessInterceptor.HEADER_AUTHORIZATION, "Bearer " + jwtWithRole("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("No active lock found for bookingId: " + bookingId));
    }

    @Test
    void getLockDetailsApi_whenInternalAccessHeadersAreMissing_returnsForbidden() throws Exception {
        mockMvc.perform(get("/internal/locks")
                        .param("bookingId", UUID.randomUUID().toString()))
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
