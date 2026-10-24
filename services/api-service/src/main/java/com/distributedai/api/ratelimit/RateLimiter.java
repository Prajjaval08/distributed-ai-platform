package com.distributedai.api.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Fixed-window counter rate limiter (Section 15). Redis INCR + EXPIRE
 * gives an atomic, race-free counter per window in two round-trips.
 * Known limitation: a burst spanning the boundary between two windows
 * can momentarily allow close to 2x the configured limit - acceptable
 * for this stage; a sliding-window or token-bucket algorithm would be
 * the upgrade path if that mattered.
 */
@Component
public class RateLimiter {

    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redisTemplate;

    public RateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public RateLimitResult checkAndIncrement(String scope, String identifier, int limit, Duration window) {
        String key = KEY_PREFIX + scope + ":" + identifier;

        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (count == null) {
                // Redis unavailable - fail open rather than block all traffic.
                // A rate limiter that takes the whole API down when Redis is
                // briefly unreachable is a worse outcome than a temporarily
                // unenforced limit. Circuit-breaker-aware degradation is
                // Phase 13's concern; this is the deliberate default until then.
                return new RateLimitResult(true, 0);
            }

            if (count == 1L) {
                // First request in this window - set the expiry once.
                redisTemplate.expire(key, window);
            }

            if (count > limit) {
                Long ttl = redisTemplate.getExpire(key);
                long remaining = (ttl != null && ttl > 0) ? ttl : window.getSeconds();
                return new RateLimitResult(false, remaining);
            }

            return new RateLimitResult(true, 0);
        } catch (RuntimeException e) {
            // Chaos-testing fix (Phase 21 gap): Lettuce throws a
            // RedisConnectionException when Redis is down instead of
            // returning null, so the null check above never ran and the
            // exception propagated as a 500. Fail open, per the Phase 10
            // design: allow the request and let the limit be unenforced
            // until Redis recovers.
            return new RateLimitResult(true, 0);
        }
    }
}