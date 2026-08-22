package com.distributedai.api.ai;

/**
 * Abstraction over any AI generation backend (Rule 6). The rest of the
 * system depends only on this interface, never on a specific provider -
 * see Section 1: "the AI provider is treated as a pluggable, unreliable
 * external dependency."
 */
public interface AiProvider {
    AiGenerationResult generate(AiGenerationRequest request);
}