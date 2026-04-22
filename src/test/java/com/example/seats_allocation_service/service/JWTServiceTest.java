package com.example.seats_allocation_service.service;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JWTServiceTest {
    private static final String JWT_SECRET = "dev-secret-change-me";
    private static final String JWT_ISSUER = "user-service";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final JWTService jwtService = new JWTService(JWT_SECRET, JWT_ISSUER, objectMapper);

    @Test
    void validateAndExtractClaims_whenTokenIsValid_returnsClaims() throws Exception {
        String token = signedJwt(Map.of(
                "iss", JWT_ISSUER,
                "exp", (System.currentTimeMillis() / 1000L) + 3600,
                "role", "ADMIN"
        ));

        Map<String, Object> claims = jwtService.validateAndExtractClaims(token);

        assertEquals(JWT_ISSUER, claims.get("iss"));
        assertEquals("ADMIN", claims.get("role"));
    }

    @Test
    void validateAndExtractClaims_whenSignatureIsInvalid_throwsException() throws Exception {
        String token = signedJwtWithSecret(Map.of(
                "iss", JWT_ISSUER,
                "exp", (System.currentTimeMillis() / 1000L) + 3600,
                "role", "ADMIN"
        ), "wrong-secret");

        assertThrows(IllegalArgumentException.class, () -> jwtService.validateAndExtractClaims(token));
    }

    @Test
    void validateAndExtractClaims_whenTokenIsExpired_throwsException() throws Exception {
        String token = signedJwt(Map.of(
                "iss", JWT_ISSUER,
                "exp", (System.currentTimeMillis() / 1000L) - 60,
                "role", "ADMIN"
        ));

        assertThrows(IllegalArgumentException.class, () -> jwtService.validateAndExtractClaims(token));
    }

    private String signedJwt(Map<String, Object> payload) throws Exception {
        return signedJwtWithSecret(payload, JWT_SECRET);
    }

    private String signedJwtWithSecret(Map<String, Object> payload, String secret) throws Exception {
        String headerJson = objectMapper.writeValueAsString(Map.of("alg", "HS256", "typ", "JWT"));
        String payloadJson = objectMapper.writeValueAsString(payload);
        String header = base64Url(headerJson);
        String body = base64Url(payloadJson);
        String unsignedToken = header + "." + body;
        String signature = base64Url(hmacSha256(unsignedToken, secret));
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
