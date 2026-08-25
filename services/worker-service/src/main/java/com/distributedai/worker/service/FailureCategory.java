package com.distributedai.worker.service;

public enum FailureCategory {
    TRANSIENT,   // timeout, temporary 5xx, connection reset -> retry
    PERMANENT,   // invalid prompt, unsupported model -> fail, no retry
    DEPENDENCY,  // provider/storage unavailable -> retry
    INTERNAL     // unexpected exception -> retry carefully
}