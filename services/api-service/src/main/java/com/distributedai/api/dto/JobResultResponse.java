package com.distributedai.api.dto;

import java.util.UUID;

public record JobResultResponse(
        UUID jobId,
        String status,
        String resultUrl
) {}