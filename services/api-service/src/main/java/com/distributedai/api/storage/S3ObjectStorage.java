package com.distributedai.api.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.net.URI;
import java.time.Duration;

@Component
public class S3ObjectStorage implements ObjectStorage {

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;
    private final long presignedExpirySeconds;

    public S3ObjectStorage(
            @Value("${object-storage.endpoint}") String endpoint,
            @Value("${object-storage.bucket}") String bucket,
            @Value("${object-storage.access-key}") String accessKey,
            @Value("${object-storage.secret-key}") String secretKey,
            @Value("${object-storage.region}") String region,
            @Value("${object-storage.presigned-url-expiry-seconds}") long presignedExpirySeconds
    ) {
        this.bucket = bucket;
        this.presignedExpirySeconds = presignedExpirySeconds;

        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));

        // Forces path-style URLs (http://host:port/bucket/key) instead of
        // virtual-hosted-style (http://bucket.host:port/key). Required for
        // reliable local MinIO usage - virtual-hosted style depends on
        // wildcard DNS resolution for "<bucket>.localhost" that isn't
        // guaranteed to work consistently across browsers/OS configurations.
        S3Configuration pathStyleConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();

               this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(credentials)
                .region(Region.of(region))
                .serviceConfiguration(pathStyleConfig)
                .build();

        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(credentials)
                .region(Region.of(region))
                .serviceConfiguration(pathStyleConfig)
                .build();
    }

    @Override
    public String upload(String key, byte[] data, String contentType) {
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .contentType(contentType)
                            .build(),
                    RequestBody.fromBytes(data)
            );

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(presignedExpirySeconds))
                    .getObjectRequest(GetObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build())
                    .build();

            return presigner.presignGetObject(presignRequest).url().toString();

        } catch (Exception ex) {
            throw new ObjectStorageException("Failed to upload object with key: " + key, ex);
        }
    }
}