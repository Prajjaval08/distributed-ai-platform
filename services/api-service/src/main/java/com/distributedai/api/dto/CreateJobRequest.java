package com.distributedai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateJobRequest(

        @NotBlank(message = "Prompt is required")
        String prompt,

        @NotBlank(message = "Model is required")
        String model,

        // Nullable - defaults to NORMAL in the service layer if omitted,
        // matching Section 10.1's example payload where priority is present
        // but the field is logically optional.
        String priority,

        Map<String, Object> parameters
) {}