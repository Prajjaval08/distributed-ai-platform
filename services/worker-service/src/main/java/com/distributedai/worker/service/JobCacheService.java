package com.distributedai.worker.service;

import com.distributedai.worker.entity.Job;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
public class JobCacheService {

    private static final String KEY_PREFIX = "job:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final long ttlSeconds;

    public JobCacheService(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            @Value("${cache.job-ttl-seconds}") long ttlSeconds
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttlSeconds = ttlSeconds;
    }

    public void put(Job job) {
        try {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("jobId", job.getId().toString());
            node.put("prompt", job.getPrompt());
            node.put("model", job.getModel());
            node.put("status", job.getStatus().name());
            node.put("priority", job.getPriority().name());
            node.put("retryCount", job.getRetryCount());
            node.put("resultUrl", job.getResultUrl());
            node.put("errorCode", job.getErrorCode());
            node.put("errorMessage", job.getErrorMessage());
            node.putPOJO("parameters", job.getParameters());
            node.put("createdAt", job.getCreatedAt() != null ? job.getCreatedAt().toString() : null);
            node.put("startedAt", job.getStartedAt() != null ? job.getStartedAt().toString() : null);
            node.put("completedAt", job.getCompletedAt() != null ? job.getCompletedAt().toString() : null);

            String json = objectMapper.writeValueAsString(node);
            String key = KEY_PREFIX + job.getUserId() + ":" + job.getId();
            redisTemplate.opsForValue().set(key, json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception ex) {
            // Cache write failure never breaks job processing - PostgreSQL
            // is already durably updated, which is what matters.
        }
    }
}