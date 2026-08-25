package com.distributedai.worker.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff with jitter (Section 17.2). Jitter exists so a burst
 * of jobs failing simultaneously doesn't retry in lockstep and re-create
 * the overload that caused the failure in the first place.
 */
@Component
public class BackoffCalculator {

    private final long baseDelayMs;
    private final long maxDelayMs;
    private final double jitterFactor;

    public BackoffCalculator(
            @Value("${retry.base-delay-ms}") long baseDelayMs,
            @Value("${retry.max-delay-ms}") long maxDelayMs,
            @Value("${retry.jitter-factor}") double jitterFactor
    ) {
        this.baseDelayMs = baseDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.jitterFactor = jitterFactor;
    }

    /**
     * attempt is 1-based (first retry = attempt 1).
     */
    public long delayMillis(int attempt) {
        long exponential = baseDelayMs * (1L << Math.max(0, attempt - 1));
        long capped = Math.min(exponential, maxDelayMs);

        double jitterRange = capped * jitterFactor;
        double jitter = ThreadLocalRandom.current().nextDouble(-jitterRange, jitterRange);

        return Math.max(0, Math.round(capped + jitter));
    }
}