package com.distributedai.api.exception;

import java.util.UUID;

public class DuplicateIdempotencyKeyException extends RuntimeException {
    private final UUID existingJobId;

    public DuplicateIdempotencyKeyException(UUID existingJobId) {
        this.existingJobId = existingJobId;
    }

    public UUID existingJobId() {
        return existingJobId;
    }
}