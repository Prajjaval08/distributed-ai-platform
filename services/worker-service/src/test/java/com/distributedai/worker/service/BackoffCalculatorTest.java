package com.distributedai.worker.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffCalculatorTest {

    private final BackoffCalculator calculator = new BackoffCalculator(2000, 10000, 0.2);

    @Test
    void firstAttemptDelayIsNearBaseDelay() {
        long delay = calculator.delayMillis(1);
        // base=2000ms, jitter=±20% -> expect range [1600, 2400]
        assertThat(delay).isBetween(1600L, 2400L);
    }

    @Test
    void delayGrowsExponentiallyBeforeCap() {
        long attempt2 = calculator.delayMillis(2); // base * 2 = 4000ms nominal
        assertThat(attempt2).isBetween(3200L, 4800L);
    }

    @Test
    void delayNeverExceedsMaxDelay() {
        long attempt10 = calculator.delayMillis(10); // would be huge uncapped
        // max=10000ms, jitter=±20% -> hard ceiling around 12000ms
        assertThat(attempt10).isLessThanOrEqualTo(12000L);
    }

    @RepeatedTest(20)
    void delayIsNeverNegative() {
        assertThat(calculator.delayMillis(3)).isGreaterThanOrEqualTo(0L);
    }
}