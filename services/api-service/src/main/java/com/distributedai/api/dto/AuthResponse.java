package com.distributedai.api.dto;

public record AuthResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String email,
        String role
) {
    public static AuthResponse of(String token, long expiresInSeconds, String email, String role) {
        return new AuthResponse(token, "Bearer", expiresInSeconds, email, role);
    }
}