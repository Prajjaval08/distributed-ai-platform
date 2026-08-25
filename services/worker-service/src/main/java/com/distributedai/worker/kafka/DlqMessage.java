package com.distributedai.worker.kafka;

import java.util.UUID;

public record DlqMessage(
        UUID jobId,
        UUID userId,
        String prompt,
        String model,
        String failureCategory,
        int retryCount,
        String errorMessage
) {}