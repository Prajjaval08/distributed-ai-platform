package com.distributedai.api.repository;

import com.distributedai.api.entity.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    // Ownership-scoped lookup - a job belonging to another user simply
    // won't match, letting us return a uniform 404 either way (Section 25.2).
    Optional<Job> findByIdAndUserId(UUID id, UUID userId);

    // Backs job history pagination (Section 11.5's idx_jobs_user_created index
    // is specifically shaped for this: user_id + created_at DESC).
    Page<Job> findByUserId(UUID userId, Pageable pageable);
}