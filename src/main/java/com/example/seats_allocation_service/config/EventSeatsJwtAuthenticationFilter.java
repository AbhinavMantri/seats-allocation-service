package com.example.seats_allocation_service.config;

import com.example.seats_allocation_service.service.JWTService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class EventSeatsJwtAuthenticationFilter extends OncePerRequestFilter {
    public static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String UNAUTHORIZED_MESSAGE = "Unauthorized: valid JWT token is required";

    private final JWTService jwtService;

    public EventSeatsJwtAuthenticationFilter(JWTService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String authorizationHeader = request.getHeader(HEADER_AUTHORIZATION);
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response);
            return;
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        try {
            jwtService.validateAndExtractClaims(token);
        } catch (IllegalArgumentException ex) {
            writeUnauthorized(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"status\":\"FAILURE\",\"message\":\"" + UNAUTHORIZED_MESSAGE + "\"}");
    }
}
