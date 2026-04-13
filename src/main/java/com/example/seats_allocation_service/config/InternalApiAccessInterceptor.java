package com.example.seats_allocation_service.config;

import com.example.seats_allocation_service.models.UserRole;
import com.example.seats_allocation_service.service.JWTService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

@Component
public class InternalApiAccessInterceptor implements HandlerInterceptor {
    public static final String HEADER_SERVICE_NAME = "X-Service-Name";
    public static final String HEADER_SERVICE_TOKEN = "X-Service-Token";
    public static final String HEADER_AUTHORIZATION = "Authorization";
    private static final Set<UserRole> ALLOWED_INTERNAL_ROLES = Set.of(UserRole.ADMIN, UserRole.ORGANISER);

    private final JWTService jwtService;
    private final Map<String, String> serviceTokens;

    @Autowired
    public InternalApiAccessInterceptor(Environment environment, JWTService jwtService) {
        this.jwtService = jwtService;
        this.serviceTokens = Binder.get(environment)
                .bind("internal.api.service-tokens", Bindable.mapOf(String.class, String.class))
                .orElseGet(Map::of);
    }

    public InternalApiAccessInterceptor(Map<String, String> serviceTokens, JWTService jwtService) {
        this.jwtService = jwtService;
        this.serviceTokens = serviceTokens == null ? Map.of() : Map.copyOf(serviceTokens);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        boolean trustedService = isServiceWhitelisted(
                request.getHeader(HEADER_SERVICE_NAME),
                request.getHeader(HEADER_SERVICE_TOKEN)
        );
        if (request.getRequestURI().startsWith("/internal/seats/") && trustedService) {
            return true;
        }

        if (trustedService && isRoleAllowedFromJwt(request.getHeader(HEADER_AUTHORIZATION))) {
            return true;
        }

        writeForbidden(response);
        return false;
    }

    private boolean isRoleAllowedFromJwt(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            return false;
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        Map<String, Object> claims;
        try {
            claims = jwtService.validateAndExtractClaims(token);
        } catch (IllegalArgumentException ex) {
            return false;
        }

        Object roleClaim = claims.get("role");
        if (!(roleClaim instanceof String role) || role.isBlank()) {
            return false;
        }
        try {
            UserRole userRole = UserRole.valueOf(role);
            return ALLOWED_INTERNAL_ROLES.contains(userRole);
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private boolean isServiceWhitelisted(String serviceName, String serviceToken) {
        if (serviceName == null || serviceName.isBlank() || serviceToken == null || serviceToken.isBlank()) {
            return false;
        }
        String expectedToken = serviceTokens.get(serviceName);
        return expectedToken != null && expectedToken.equals(serviceToken);
    }

    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":\"FAILURE\",\"message\":\"Forbidden: internal endpoint access denied\"}");
    }
}
