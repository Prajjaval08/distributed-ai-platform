package com.distributedai.api.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    // Placeholder for Section 10's GET /api/v1/users/me - real DTO-based
    // implementation with usage stats etc. arrives when it's actually needed.
    // This exists in Phase 3 solely to prove the JWT filter populates the
    // security context correctly.
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication) {
        UUID userId = (UUID) authentication.getPrincipal();
        return Map.of("userId", userId.toString());
    }
}