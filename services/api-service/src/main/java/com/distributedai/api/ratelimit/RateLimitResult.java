package com.distributedai.api.ratelimit;

public record RateLimitResult(boolean allowed, long remainingSeconds) {}