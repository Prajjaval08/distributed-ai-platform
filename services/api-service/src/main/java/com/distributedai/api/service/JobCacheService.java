package com.distributedai.api.service;

import com.distributedai.api.dto.JobResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

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

    public JobResponse get(UUID userId, UUID jobId) {
        String json = redisTemplate.opsForValue().get(key(userId, jobId));
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, JobResponse.class);
        } catch (Exception ex) {
            return null;
        }
    }

    public void put(UUID userId, JobResponse job) {
        try {
            String json = objectMapper.writeValueAsString(job);
            redisTemplate.opsForValue().set(key(userId, job.jobId()), json, Duration.ofSeconds(ttlSeconds));
        } catch (Exception ex) {
            // Cache write failure should never break the request.
        }
    }

    private String key(UUID userId, UUID jobId) {
        return KEY_PREFIX + userId + ":" + jobId;
    }
}