package com.distributedai.worker.kafka;

import java.util.Map;
import java.util.UUID;

public record JobMessage(
        UUID jobId,
        UUID userId,
        String prompt,
        String model,
        String priority,
        Map<String, Object> parameters
) {}
