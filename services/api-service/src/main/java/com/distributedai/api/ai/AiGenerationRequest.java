package com.distributedai.api.ai;

import java.util.Map;

public record AiGenerationRequest(
        String prompt,
        String model,
        Map<String, Object> parameters
) {}