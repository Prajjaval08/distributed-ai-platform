package com.distributedai.worker.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class ResilienceConfig {

    private static final Logger log = LoggerFactory.getLogger(ResilienceConfig.class);

    @Bean
    public CircuitBreakerConfig circuitBreakerConfig(
            @Value("${resilience.circuit-breaker-failure-rate-threshold}") float failureRateThreshold,
            @Value("${resilience.circuit-breaker-wait-duration-seconds}") long waitDurationSeconds,
            @Value("${resilience.circuit-breaker-min-calls}") int minCalls
    ) {
        return CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(Duration.ofSeconds(waitDurationSeconds))
                .minimumNumberOfCalls(minCalls)
                .slidingWindowSize(minCalls)
                .permittedNumberOfCallsInHalfOpenState(2)
                .build();
    }

    @Bean
    @Qualifier("aiProviderCircuitBreaker")
    public CircuitBreaker aiProviderCircuitBreaker(CircuitBreakerConfig config) {
        CircuitBreaker cb = CircuitBreaker.of("aiProvider", config);
        registerStateLogging(cb);
        return cb;
    }

    @Bean
    @Qualifier("objectStorageCircuitBreaker")
    public CircuitBreaker objectStorageCircuitBreaker(CircuitBreakerConfig config) {
        CircuitBreaker cb = CircuitBreaker.of("objectStorage", config);
        registerStateLogging(cb);
        return cb;
    }

    @Bean
    @Qualifier("aiProviderTimeLimiter")
    public TimeLimiter aiProviderTimeLimiter(
            @Value("${resilience.ai-provider-timeout-seconds}") long timeoutSeconds
    ) {
        return TimeLimiter.of("aiProvider", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(timeoutSeconds))
                .build());
    }

    @Bean
    @Qualifier("objectStorageTimeLimiter")
    public TimeLimiter objectStorageTimeLimiter(
            @Value("${resilience.object-storage-timeout-seconds}") long timeoutSeconds
    ) {
        return TimeLimiter.of("objectStorage", TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofSeconds(timeoutSeconds))
                .build());
    }

    private void registerStateLogging(CircuitBreaker cb) {
        cb.getEventPublisher().onStateTransition(event ->
                log.warn("Circuit breaker [{}] transitioned: {} -> {}",
                        cb.getName(),
                        event.getStateTransition().getFromState(),
                        event.getStateTransition().getToState()));
    }
}