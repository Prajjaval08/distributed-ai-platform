package com.distributedai.api.ai;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;

/**
 * Real AI provider backed by OpenAI's image generation API. Only active
 * when ai.provider.type=openai (see application.yml). Requires a genuine
 * AI_PROVIDER_API_KEY - calling this incurs real cost on your OpenAI account.
 */
@Component
@ConditionalOnProperty(name = "ai.provider.type", havingValue = "openai")
public class OpenAiImageProvider implements AiProvider {

    private final RestClient restClient;
    private final String defaultModel;

    public OpenAiImageProvider(
            @Value("${ai.provider.url}") String baseUrl,
            @Value("${ai.provider.api-key}") String apiKey,
            @Value("${ai.provider.default-model}") String defaultModel
    ) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "ai.provider.type=openai requires AI_PROVIDER_API_KEY to be set");
        }
        this.defaultModel = defaultModel;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    @Override
    public AiGenerationResult generate(AiGenerationRequest request) {
        String model = (request.model() != null && !request.model().isBlank())
                ? request.model()
                : defaultModel;

        try {
            JsonNode response = restClient.post()
                    .uri("/images/generations")
                    .body(Map.of(
                            "model", model,
                            "prompt", request.prompt(),
                            "n", 1,
                            "size", "1024x1024",
                            "response_format", "b64_json"
                    ))
                    .retrieve()
                    .body(JsonNode.class);

            String base64Data = response.path("data").get(0).path("b64_json").asText();
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);

            return new AiGenerationResult(imageBytes, "image/png");

        } catch (Exception ex) {
            // Broad catch is deliberate for now - Phase 8 introduces real
            // TRANSIENT/PERMANENT/DEPENDENCY classification. Right now any
            // failure here is simply "the AI provider call failed."
            throw new AiProviderException("OpenAI image generation failed: " + ex.getMessage(), ex);
        }
    }
}