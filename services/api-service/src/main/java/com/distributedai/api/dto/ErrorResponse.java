package com.distributedai.api.dto;

import io.opentelemetry.api.trace.Span;

import java.time.Instant;

public record ErrorResponse(
        String errorCode,
        String message,
        String traceId,
        Instant timestamp
) {
    public static ErrorResponse of(String errorCode, String message) {

        String traceId = Span.current()
                .getSpanContext()
                .getTraceId();

        return new ErrorResponse(
                errorCode,
                message,
                traceId,
                Instant.now()
        );
    }
}