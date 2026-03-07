package com.example.seats_allocation_service.service;

import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;

@Service
public class JWTService {
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final byte[] secretKey;
    private final String issuer;
    private final ObjectMapper objectMapper;

    @Autowired
    public JWTService(
            @Value("${security.jwt.secret:dev-secret-change-me}") String secret,
            @Value("${security.jwt.issuer:user-service}") String issuer,
            ObjectMapper objectMapper
    ) {
        this.secretKey = secret.getBytes(StandardCharsets.UTF_8);
        this.issuer = issuer;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> validateAndExtractClaims(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid JWT format");
            }

            String unsignedToken = parts[0] + "." + parts[1];
            byte[] expectedSignature = hmacSha256(unsignedToken);
            byte[] providedSignature = base64UrlDecode(parts[2]);
            if (!MessageDigest.isEqual(expectedSignature, providedSignature)) {
                throw new IllegalArgumentException("Invalid JWT signature");
            }

            String payloadJson = new String(base64UrlDecode(parts[1]), StandardCharsets.UTF_8);
            Map<String, Object> claims = objectMapper.readValue(payloadJson, Map.class);

            Object issuerClaim = claims.get("iss");
            if (!(issuerClaim instanceof String issuerValue) || !issuer.equals(issuerValue)) {
                throw new IllegalArgumentException("Invalid JWT issuer");
            }

            long now = Instant.now().getEpochSecond();
            long exp = ((Number) claims.get("exp")).longValue();
            if (exp < now) {
                throw new IllegalArgumentException("JWT has expired");
            }

            return claims;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid JWT token", e);
        }
    }

    private byte[] hmacSha256(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secretKey, HMAC_ALGORITHM));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign JWT", e);
        }
    }

    private byte[] base64UrlDecode(String value) {
        return Base64.getUrlDecoder().decode(value);
    }
}
