package com.distributedai.worker.service;

import com.distributedai.worker.ai.AiProviderException;
import com.distributedai.worker.storage.ObjectStorageException;

/**
 * Central place that maps any exception thrown during job processing to a
 * FailureCategory (Section 17.1). Anything not explicitly recognized falls
 * back to INTERNAL - the safest default (retried carefully, never silently
 * dropped, never assumed permanent without evidence).
 */
public class FailureClassifier {

    public static FailureCategory classify(Throwable ex) {
        if (ex instanceof AiProviderException aiEx) {
            return aiEx.category();
        }
        if (ex instanceof ObjectStorageException storageEx) {
            return storageEx.category();
        }
        if (ex instanceof IllegalArgumentException) {
            // Bad input the job was created with - retrying won't help.
            return FailureCategory.PERMANENT;
        }
        return FailureCategory.INTERNAL;
    }

    public static boolean isRetryable(FailureCategory category) {
        return category == FailureCategory.TRANSIENT
                || category == FailureCategory.DEPENDENCY
                || category == FailureCategory.INTERNAL;
    }
}