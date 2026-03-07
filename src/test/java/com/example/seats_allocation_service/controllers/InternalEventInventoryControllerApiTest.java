package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.config.InternalApiAccessInterceptor;
import com.example.seats_allocation_service.exceptions.EventInventoryAlreadyExistsException;
import com.example.seats_allocation_service.models.EventInventoryContext;
import com.example.seats_allocation_service.service.EventInventoryService;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class InternalEventInventoryControllerApiTest {
    private static final String JWT_SECRET = "dev-secret-change-me";
    private static final String JWT_ISSUER = "user-service";


    @Mock
    private EventInventoryService eventInventoryService;

    @InjectMocks
    private InternalEventInventoryController internalEventInventoryController;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(internalEventInventoryController)
                .addInterceptors(new InternalApiAccessInterceptor(
                        Map.of("inventory-service", "svc-token"),
                        new JWTService(JWT_SECRET, JWT_ISSUER, objectMapper)
                ))
                .build();
    }

    @Test
    void initializeInventoryApi_whenCalledByWhitelistedServiceAndAdminRole_returns200AndResponseBody() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();

        EventInventoryContext context = new EventInventoryContext();
        context.setId(eventId);
        context.setVenueId(venueId);
        context.setCurrency("USD");

        when(eventInventoryService.initializeInventory(eq(eventId), any())).thenReturn(context);

        String requestJson = """
                {
                  "venueId": "%s",
                  "currency": "USD",
                  "pricing": [
                    {
                      "sectionId": "%s",
                      "priceCents": 5000
                    }
                  ]
                }
                """.formatted(venueId, UUID.randomUUID());

        mockMvc.perform(post("/internal/events/{eventId}/inventory/init", eventId)
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_NAME, "inventory-service")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_TOKEN, "svc-token")
                        .header(InternalApiAccessInterceptor.HEADER_AUTHORIZATION, "Bearer " + jwtWithRole("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Event initiated successfully"))
                .andExpect(jsonPath("$.eventInventoryContext.id").value(eventId.toString()))
                .andExpect(jsonPath("$.eventInventoryContext.venueId").value(venueId.toString()))
                .andExpect(jsonPath("$.eventInventoryContext.currency").value("USD"));
    }

    @Test
    void initializeInventoryApi_whenInventoryAlreadyExistsAndAccessIsAuthorized_returns409AndFailureBody() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();

        when(eventInventoryService.initializeInventory(eq(eventId), any()))
                .thenThrow(new EventInventoryAlreadyExistsException("inventory already exists"));

        String requestJson = """
                {
                  "venueId": "%s",
                  "currency": "USD",
                  "pricing": []
                }
                """.formatted(venueId);

        mockMvc.perform(post("/internal/events/{eventId}/inventory/init", eventId)
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_NAME, "inventory-service")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_TOKEN, "svc-token")
                        .header(InternalApiAccessInterceptor.HEADER_AUTHORIZATION, "Bearer " + jwtWithRole("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("inventory already exists"));
    }

    @Test
    void initializeInventoryApi_whenJwtIsPresentWithoutWhitelistedService_returns403() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();

        String requestJson = """
                {
                  "venueId": "%s",
                  "currency": "USD",
                  "pricing": []
                }
                """.formatted(venueId);

        mockMvc.perform(post("/internal/events/{eventId}/inventory/init", eventId)
                        .header(InternalApiAccessInterceptor.HEADER_AUTHORIZATION, "Bearer " + jwtWithRole("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Forbidden: internal endpoint access denied"));
    }

    @Test
    void initializeInventoryApi_whenServiceIsWhitelistedWithoutAllowedRole_returns403() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();

        String requestJson = """
                {
                  "venueId": "%s",
                  "currency": "USD",
                  "pricing": []
                }
                """.formatted(venueId);

        mockMvc.perform(post("/internal/events/{eventId}/inventory/init", eventId)
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_NAME, "inventory-service")
                        .header(InternalApiAccessInterceptor.HEADER_SERVICE_TOKEN, "svc-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("Forbidden: internal endpoint access denied"));
    }

    @Test
    void initializeInventoryApi_whenNoWhitelistedServiceOrAllowedRole_returns403() throws Exception {
        UUID eventId = UUID.randomUUID();
        UUID venueId = UUID.randomUUID();

        String requestJson = """
                {
                  "venueId": "%s",
                  "currency": "USD",
                  "pricing": []
                }
                """.formatted(venueId);

        mockMvc.perform(post("/internal/events/{eventId}/inventory/init", eventId)
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
