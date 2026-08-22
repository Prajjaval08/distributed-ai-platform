package com.distributedai.api.kafka;

public class JobPublishException extends RuntimeException {
    public JobPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}