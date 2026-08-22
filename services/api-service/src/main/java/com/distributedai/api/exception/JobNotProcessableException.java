package com.distributedai.api.exception;

public class JobNotProcessableException extends RuntimeException {
    public JobNotProcessableException(String currentStatus) {
        super("Job cannot be processed from status: " + currentStatus);
    }
}