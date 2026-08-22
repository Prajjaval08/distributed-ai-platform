package com.distributedai.worker.ai;

import java.util.Map;

public record AiGenerationRequest(String prompt, String model, Map<String, Object> parameters) {}
