package com.distributedai.worker.storage;

public interface ObjectStorage {
    String upload(String key, byte[] data, String contentType);
}
