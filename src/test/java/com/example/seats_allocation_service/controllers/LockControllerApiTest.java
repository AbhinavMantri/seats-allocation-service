package com.example.seats_allocation_service.controllers;

import com.example.seats_allocation_service.dtos.LockDetail;
import com.example.seats_allocation_service.exceptions.SeatsNotFoundException;
import com.example.seats_allocation_service.service.LockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class LockControllerApiTest {

    @Mock
    private LockService lockService;

    @InjectMocks
    private LockController lockController;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(lockController).build();
    }

    @Test
    void getLockDetailsApi_whenSuccessful_returnsPayload() throws Exception {
        UUID bookingId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID seatId = UUID.randomUUID();
        LockDetail lockDetail = LockDetail.builder()
                .bookingId(bookingId)
                .eventId(eventId)
                .seatIds(List.of(seatId))
                .lockExpiresAt("2026-03-21T10:15:30Z")
                .status("LOCKED")
                .build();
        when(lockService.getLockDetails(bookingId)).thenReturn(lockDetail);

        mockMvc.perform(get("/internal/locks")
                        .param("bookingId", bookingId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("Lock details fetched successfully"))
                .andExpect(jsonPath("$.result.bookingId").value(bookingId.toString()))
                .andExpect(jsonPath("$.result.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.result.seatIds[0]").value(seatId.toString()))
                .andExpect(jsonPath("$.result.lockExpiresAt").value("2026-03-21T10:15:30Z"))
                .andExpect(jsonPath("$.result.status").value("LOCKED"));
    }

    @Test
    void getLockDetailsApi_whenLockIsMissing_returnsNotFound() throws Exception {
        UUID bookingId = UUID.randomUUID();
        when(lockService.getLockDetails(bookingId))
                .thenThrow(new SeatsNotFoundException("No active lock found for bookingId: " + bookingId));

        mockMvc.perform(get("/internal/locks")
                        .param("bookingId", bookingId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("FAILURE"))
                .andExpect(jsonPath("$.message").value("No active lock found for bookingId: " + bookingId));
    }
}
