package com.distributedai.worker.ai;

public interface AiProvider {
    AiGenerationResult generate(AiGenerationRequest request);
}
