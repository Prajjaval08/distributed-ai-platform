package com.distributedai.api.repository;

import com.distributedai.api.entity.IdempotencyKey;
import com.distributedai.api.entity.IdempotencyKeyId;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, IdempotencyKeyId> {
    Optional<IdempotencyKey> findByKeyAndUserId(String key, UUID userId);
}