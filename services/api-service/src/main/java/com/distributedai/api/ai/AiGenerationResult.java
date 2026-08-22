package com.distributedai.api.ai;

public record AiGenerationResult(
        byte[] data,
        String contentType
) {}