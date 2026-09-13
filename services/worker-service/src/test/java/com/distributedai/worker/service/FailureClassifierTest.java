package com.distributedai.worker.service;

import com.distributedai.worker.ai.AiProviderException;
import com.distributedai.worker.storage.ObjectStorageException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class FailureClassifierTest {

    @Test
    void aiProviderExceptionRetainsItsOwnCategory() {
        var ex = new AiProviderException(
                "boom",
                FailureCategory.PERMANENT
        );

        assertThat(FailureClassifier.classify(ex))
                .isEqualTo(FailureCategory.PERMANENT);
    }

    @Test
    void objectStorageExceptionRetainsItsOwnCategory() {
        var ex = new ObjectStorageException(
                "boom",
                FailureCategory.DEPENDENCY,
                new RuntimeException()
        );

        assertThat(FailureClassifier.classify(ex))
                .isEqualTo(FailureCategory.DEPENDENCY);
    }

    @Test
    void timeoutExceptionIsTransient() {
        assertThat(FailureClassifier.classify(new TimeoutException()))
                .isEqualTo(FailureCategory.TRANSIENT);
    }

    @Test
    void wrappedTimeoutExceptionIsStillTransient() {
        var wrapped = new RuntimeException(new TimeoutException());

        assertThat(FailureClassifier.classify(wrapped))
                .isEqualTo(FailureCategory.TRANSIENT);
    }

    @Test
    void illegalArgumentIsPermanent() {
        assertThat(FailureClassifier.classify(new IllegalArgumentException()))
                .isEqualTo(FailureCategory.PERMANENT);
    }

    @Test
    void unknownExceptionDefaultsToInternal() {
        assertThat(
                FailureClassifier.classify(
                        new RuntimeException("mystery")
                )
        ).isEqualTo(FailureCategory.INTERNAL);
    }

    @Test
    void transientAndDependencyAndInternalAreRetryable() {
        assertThat(
                FailureClassifier.isRetryable(FailureCategory.TRANSIENT)
        ).isTrue();

        assertThat(
                FailureClassifier.isRetryable(FailureCategory.DEPENDENCY)
        ).isTrue();

        assertThat(
                FailureClassifier.isRetryable(FailureCategory.INTERNAL)
        ).isTrue();
    }

    @Test
    void permanentIsNotRetryable() {
        assertThat(
                FailureClassifier.isRetryable(FailureCategory.PERMANENT)
        ).isFalse();
    }
}
