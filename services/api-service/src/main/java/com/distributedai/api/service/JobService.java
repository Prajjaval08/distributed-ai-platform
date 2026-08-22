package com.distributedai.api.service;

import com.distributedai.api.ai.AiGenerationRequest;
import com.distributedai.api.ai.AiGenerationResult;
import com.distributedai.api.ai.AiProvider;
import com.distributedai.api.dto.CreateJobRequest;
import com.distributedai.api.entity.Job;
import com.distributedai.api.entity.JobEvent;
import com.distributedai.api.entity.JobPriority;
import com.distributedai.api.entity.JobStatus;
import com.distributedai.api.exception.JobNotCancellableException;
import com.distributedai.api.exception.JobNotFoundException;
import com.distributedai.api.exception.JobNotProcessableException;
import com.distributedai.api.exception.JobResultNotReadyException;
import com.distributedai.api.repository.JobEventRepository;
import com.distributedai.api.repository.JobRepository;
import com.distributedai.api.storage.ObjectStorage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final JobEventRepository jobEventRepository;
    private final AiProvider aiProvider;
    private final ObjectStorage objectStorage;

    public JobService(
            JobRepository jobRepository,
            JobEventRepository jobEventRepository,
            AiProvider aiProvider,
            ObjectStorage objectStorage
    ) {
        this.jobRepository = jobRepository;
        this.jobEventRepository = jobEventRepository;
        this.aiProvider = aiProvider;
        this.objectStorage = objectStorage;
    }

    @Transactional
    public Job createJob(UUID userId, CreateJobRequest request) {
        Job job = new Job();
        job.setUserId(userId);
        job.setPrompt(request.prompt());
        job.setModel(request.model());
        job.setParameters(request.parameters());
        job.setPriority(parsePriority(request.priority()));

        // status defaults to CREATED via @PrePersist
        // NOTE: does not transition to QUEUED here - that requires Kafka.

        Job saved = jobRepository.save(job);

        recordEvent(saved.getId(), "CREATED", Map.of(
                "model", saved.getModel(),
                "priority", saved.getPriority().name()
        ));

        return saved;
    }

    @Transactional(readOnly = true)
    public Job getJob(UUID userId, UUID jobId) {
        return jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(JobNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Page<Job> listJobs(UUID userId, Pageable pageable) {
        return jobRepository.findByUserId(userId, pageable);
    }

    @Transactional
    public Job cancelJob(UUID userId, UUID jobId) {
        Job job = getJob(userId, jobId);

        // Only CREATED is cancellable right now.
        if (job.getStatus() != JobStatus.CREATED) {
            throw new JobNotCancellableException(job.getStatus().name());
        }

        job.setStatus(JobStatus.CANCELLED);

        Job saved = jobRepository.save(job);

        recordEvent(
                saved.getId(),
                "CANCELLED",
                Map.of("previousStatus", "CREATED")
        );

        return saved;
    }

    @Transactional(readOnly = true)
    public Job getResult(UUID userId, UUID jobId) {
        Job job = getJob(userId, jobId);

        if (job.getStatus() != JobStatus.COMPLETED) {
            throw new JobResultNotReadyException(job.getStatus().name());
        }

        return job;
    }

    /**
     * TEMPORARY - Phase 5 only.
     *
     * Synchronously processes a CREATED job through:
     *
     * CREATED
     *    ↓
     * PROCESSING
     *    ↓
     * AI generation
     *    ↓
     * Object storage upload
     *    ↓
     * COMPLETED / FAILED
     *
     * This will be removed once Kafka + worker-service
     * handle asynchronous processing.
     */
    @Transactional
    public Job processJobManually(UUID userId, UUID jobId) {

        Job job = getJob(userId, jobId);

        // Only CREATED jobs can be manually processed.
        if (job.getStatus() != JobStatus.CREATED) {
            throw new JobNotProcessableException(
                    job.getStatus().name()
            );
        }

        // CREATED -> PROCESSING
        job.setStatus(JobStatus.PROCESSING);
        job.setStartedAt(LocalDateTime.now());

        jobRepository.save(job);

        recordEvent(
                job.getId(),
                "PROCESSING",
                Map.of()
        );

        try {

            // Call AI provider
            AiGenerationResult result = aiProvider.generate(
                    new AiGenerationRequest(
                            job.getPrompt(),
                            job.getModel(),
                            job.getParameters()
                    )
            );

            // Upload generated result to object storage
            String key = "jobs/"
                    + job.getId()
                    + "/result.png";

            String resultUrl = objectStorage.upload(
                    key,
                    result.data(),
                    result.contentType()
            );

            // PROCESSING -> COMPLETED
            job.setStatus(JobStatus.COMPLETED);
            job.setResultUrl(resultUrl);
            job.setCompletedAt(LocalDateTime.now());

            Job saved = jobRepository.save(job);

            recordEvent(
                    saved.getId(),
                    "COMPLETED",
                    Map.of(
                            "resultUrl",
                            resultUrl
                    )
            );

            return saved;

        } catch (Exception ex) {

            // Any error is terminal for now.
            // Retry logic will be introduced later.

            job.setStatus(JobStatus.FAILED);
            job.setErrorCode("INTERNAL_ERROR");
            job.setErrorMessage(ex.getMessage());
            job.setCompletedAt(LocalDateTime.now());

            Job saved = jobRepository.save(job);

            recordEvent(
                    saved.getId(),
                    "FAILED",
                    Map.of(
                            "errorMessage",
                            String.valueOf(ex.getMessage())
                    )
            );

            return saved;
        }
    }

    private JobPriority parsePriority(String rawPriority) {

        if (rawPriority == null || rawPriority.isBlank()) {
            return JobPriority.NORMAL;
        }

        try {
            return JobPriority.valueOf(
                    rawPriority.toUpperCase()
            );

        } catch (IllegalArgumentException ex) {

            return JobPriority.NORMAL;
        }
    }

    private void recordEvent(
            UUID jobId,
            String eventType,
            Map<String, Object> metadata
    ) {

        JobEvent event = new JobEvent();

        event.setJobId(jobId);
        event.setEventType(eventType);
        event.setMetadata(metadata);

        jobEventRepository.save(event);
    }
}