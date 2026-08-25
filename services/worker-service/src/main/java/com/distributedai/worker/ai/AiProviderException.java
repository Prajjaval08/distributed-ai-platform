package com.distributedai.worker.ai;

import com.distributedai.worker.service.FailureCategory;

public class AiProviderException extends RuntimeException {

    private final FailureCategory category;

    public AiProviderException(String message, FailureCategory category, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public AiProviderException(String message, FailureCategory category) {
        super(message);
        this.category = category;
    }

    public FailureCategory category() {
        return category;
    }
}