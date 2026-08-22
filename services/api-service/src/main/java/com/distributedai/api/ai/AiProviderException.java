package com.distributedai.api.ai;

public class AiProviderException extends RuntimeException {
    public AiProviderException(String message, Throwable cause) {
        super(message, cause);
    }

    public AiProviderException(String message) {
        super(message);
    }
}