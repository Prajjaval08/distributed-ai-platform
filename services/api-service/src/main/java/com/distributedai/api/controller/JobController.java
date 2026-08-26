package com.distributedai.api.controller;

import com.distributedai.api.dto.CreateJobRequest;
import com.distributedai.api.dto.JobResponse;
import com.distributedai.api.dto.JobResultResponse;
import com.distributedai.api.entity.Job;
import com.distributedai.api.service.JobCacheService;
import com.distributedai.api.service.JobService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestHeader;
import com.distributedai.api.exception.DuplicateIdempotencyKeyException;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;
    private final JobCacheService jobCacheService;

    public JobController(
            JobService jobService,
            JobCacheService jobCacheService
    ) {
        this.jobService = jobService;
        this.jobCacheService = jobCacheService;
    }

  @PostMapping
    public ResponseEntity<JobResponse> createJob(
            @Valid @RequestBody CreateJobRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication
    ) {
        UUID userId = currentUserId(authentication);
        try {
            Job job = jobService.createJob(userId, request, idempotencyKey);
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponse.from(job));
        } catch (DuplicateIdempotencyKeyException raced) {
            // Lost a concurrent race for this key - fetch and return the
            // job the winning request actually created.
            Job winningJob = jobService.getJob(userId, raced.existingJobId());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponse.from(winningJob));
        }
    }

    @GetMapping("/{id}")
    public JobResponse getJob(
            @PathVariable("id") UUID jobId,
            Authentication authentication
    ) {
        UUID userId = currentUserId(authentication);

        // 1. Try Redis first using user-scoped cache key.
        JobResponse cached = jobCacheService.get(userId, jobId);

        if (cached != null) {
            return cached;
        }

        // 2. Cache miss -> PostgreSQL.
        // JobService enforces ownership using userId + jobId.
        Job job = jobService.getJob(userId, jobId);

        // 3. Convert entity to DTO.
        JobResponse response = JobResponse.from(job);

        // 4. Populate Redis.
        jobCacheService.put(userId, response);

        return response;
    }

    @GetMapping
    public Page<JobResponse> listJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        UUID userId = currentUserId(authentication);

        Pageable pageable = PageRequest.of(
                page,
                size,
                Sort.by(Sort.Direction.DESC, "createdAt")
        );

        return jobService
                .listJobs(userId, pageable)
                .map(JobResponse::from);
    }

    @PostMapping("/{id}/cancel")
    public JobResponse cancelJob(
            @PathVariable("id") UUID jobId,
            Authentication authentication
    ) {
        UUID userId = currentUserId(authentication);

        Job job = jobService.cancelJob(userId, jobId);

        return JobResponse.from(job);
    }

    @GetMapping("/{id}/result")
    public JobResultResponse getResult(
            @PathVariable("id") UUID jobId,
            Authentication authentication
    ) {
        UUID userId = currentUserId(authentication);

        Job job = jobService.getResult(userId, jobId);

        return new JobResultResponse(
                job.getId(),
                job.getStatus().name(),
                job.getResultUrl()
        );
    }

    private UUID currentUserId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }
}