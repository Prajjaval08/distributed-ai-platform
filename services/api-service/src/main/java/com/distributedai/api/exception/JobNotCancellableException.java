package com.distributedai.api.exception;

public class JobNotCancellableException extends RuntimeException {
    public JobNotCancellableException(String currentStatus) {
        super("Job cannot be cancelled from status: " + currentStatus);
    }
}