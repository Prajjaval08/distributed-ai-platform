package com.distributedai.api.exception;

public class JobResultNotReadyException extends RuntimeException {
    public JobResultNotReadyException(String currentStatus) {
        super("Job result is not yet available. Current status: " + currentStatus);
    }
}