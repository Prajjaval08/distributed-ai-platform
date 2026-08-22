package com.distributedai.api.dto;

import java.time.Instant;

public record ErrorResponse(
        String errorCode,
        String message,
        String traceId,
        Instant timestamp
) {
    public static ErrorResponse of(String errorCode, String message) {
        // traceId is a placeholder until Phase 24 (OpenTelemetry) gives us a real one
        return new ErrorResponse(errorCode, message, "unavailable", Instant.now());
    }
}