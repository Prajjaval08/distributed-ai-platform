package com.distributedai.api.dto;

import com.distributedai.api.entity.Job;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record JobResponse(
        UUID jobId,
        String prompt,
        String model,
        String status,
        String priority,
        int retryCount,
        String resultUrl,
        String errorCode,
        String errorMessage,
        Map<String, Object> parameters,
        LocalDateTime createdAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt
) {
    public static JobResponse from(Job job) {
        return new JobResponse(
                job.getId(),
                job.getPrompt(),
                job.getModel(),
                job.getStatus().name(),
                job.getPriority().name(),
                job.getRetryCount(),
                job.getResultUrl(),
                job.getErrorCode(),
                job.getErrorMessage(),
                job.getParameters(),
                job.getCreatedAt(),
                job.getStartedAt(),
                job.getCompletedAt()
        );
    }
}