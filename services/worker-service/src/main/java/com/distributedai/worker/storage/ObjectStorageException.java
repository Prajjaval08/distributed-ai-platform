package com.distributedai.worker.storage;

import com.distributedai.worker.service.FailureCategory;

public class ObjectStorageException extends RuntimeException {

    private final FailureCategory category;

    public ObjectStorageException(String message, FailureCategory category, Throwable cause) {
        super(message, cause);
        this.category = category;
    }

    public FailureCategory category() {
        return category;
    }
}