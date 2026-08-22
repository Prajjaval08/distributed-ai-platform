package com.distributedai.api.controller;

import com.distributedai.api.dto.CreateJobRequest;
import com.distributedai.api.dto.JobResponse;
import com.distributedai.api.dto.JobResultResponse;
import com.distributedai.api.entity.Job;
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

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/jobs")
public class JobController {

    private final JobService jobService;

    public JobController(JobService jobService) {
        this.jobService = jobService;
    }

    @PostMapping
    public ResponseEntity<JobResponse> createJob(
            @Valid @RequestBody CreateJobRequest request,
            Authentication authentication
    ) {
        UUID userId = currentUserId(authentication);
        Job job = jobService.createJob(userId, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponse.from(job));
    }

    @GetMapping("/{id}")
    public JobResponse getJob(@PathVariable("id") UUID jobId, Authentication authentication) {
        UUID userId = currentUserId(authentication);
        Job job = jobService.getJob(userId, jobId);
        return JobResponse.from(job);
    }

    @GetMapping
    public Page<JobResponse> listJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        UUID userId = currentUserId(authentication);
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return jobService.listJobs(userId, pageable).map(JobResponse::from);
    }

    @PostMapping("/{id}/cancel")
    public JobResponse cancelJob(@PathVariable("id") UUID jobId, Authentication authentication) {
        UUID userId = currentUserId(authentication);
        Job job = jobService.cancelJob(userId, jobId);
        return JobResponse.from(job);
    }

    @GetMapping("/{id}/result")
    public JobResultResponse getResult(@PathVariable("id") UUID jobId, Authentication authentication) {
        UUID userId = currentUserId(authentication);
        Job job = jobService.getResult(userId, jobId);
        return new JobResultResponse(job.getId(), job.getStatus().name(), job.getResultUrl());
    }

    private UUID currentUserId(Authentication authentication) {
        return (UUID) authentication.getPrincipal();
    }
}