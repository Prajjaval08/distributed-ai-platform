package com.distributedai.api.storage;

/**
 * Abstraction over durable artifact storage (Rule 6). Implementations are
 * S3-compatible (real S3, Cloudflare R2, or local MinIO) - see Section 8.
 */
public interface ObjectStorage {
    /**
     * Uploads data under the given key and returns a URL the client can
     * use to retrieve it (a presigned URL with a bounded expiry, not a
     * permanently public link - see Section 25 security posture).
     */
    String upload(String key, byte[] data, String contentType);
}