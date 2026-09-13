package com.distributedai.api.controller;

import com.distributedai.api.config.BusinessMetrics;
import com.distributedai.api.ratelimit.RateLimiter;
import com.distributedai.api.security.JwtService;
import com.distributedai.api.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuthService authService;

    @MockBean
    private BusinessMetrics businessMetrics;

    @MockBean
    private RateLimiter rateLimiter;

    @MockBean
    private JwtService jwtService;

    @Test
    void register_withInvalidEmail_returns400() throws Exception {
        String body = """
                {
                    "email": "not-an-email",
                    "password": "password123"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isBadRequest());
    }

    @Test
    void register_withShortPassword_returns400() throws Exception {
        String body = """
                {
                    "email": "valid@example.com",
                    "password": "short"
                }
                """;

        mockMvc.perform(
                        post("/api/v1/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                )
                .andExpect(status().isBadRequest());
    }
}