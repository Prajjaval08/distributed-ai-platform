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
import com.distributedai.api.kafka.JobMessage;
import com.distributedai.api.kafka.JobProducer;
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
    private final JobProducer jobProducer;

    public JobService(
            JobRepository jobRepository,
            JobEventRepository jobEventRepository,
            AiProvider aiProvider,
            ObjectStorage objectStorage,
            JobProducer jobProducer
    ) {
        this.jobRepository = jobRepository;
        this.jobEventRepository = jobEventRepository;
        this.aiProvider = aiProvider;
        this.objectStorage = objectStorage;
        this.jobProducer = jobProducer;
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

        Job saved = jobRepository.save(job);
        recordEvent(saved.getId(), "CREATED", Map.of(
                "model", saved.getModel(),
                "priority", saved.getPriority().name()
        ));

        // Publish to Kafka, then transition CREATED -> QUEUED. If publish
        // throws, JobPublishException propagates and @Transactional rolls
        // back the entire method - including the CREATED row and its event.
        // See Phase 6 Design Decisions: this fails the whole request rather
        // than leaving an orphaned CREATED job when Kafka is unavailable.
        jobProducer.publish(new JobMessage(
                saved.getId(),
                saved.getUserId(),
                saved.getPrompt(),
                saved.getModel(),
                saved.getPriority().name(),
                saved.getParameters()
        ));

        saved.setStatus(JobStatus.QUEUED);
        Job queued = jobRepository.save(saved);
        recordEvent(queued.getId(), "QUEUED", Map.of());

        return queued;
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

        // CREATED and QUEUED are both cancellable (Appendix A: "cancel
        // before processing"). PROCESSING and beyond are not yet reachable
        // guards here - Phase 7 will need to reconsider this once workers
        // exist and PROCESSING becomes real.
        if (job.getStatus() != JobStatus.CREATED && job.getStatus() != JobStatus.QUEUED) {
            throw new JobNotCancellableException(job.getStatus().name());
        }

        String previousStatus = job.getStatus().name();
        job.setStatus(JobStatus.CANCELLED);
        Job saved = jobRepository.save(job);
        recordEvent(saved.getId(), "CANCELLED", Map.of("previousStatus", previousStatus));

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
     * Synchronously processes a QUEUED job through:
     *
     * QUEUED
     *    ->
     * PROCESSING
     *    ->
     * AI generation
     *    ->
     * Object storage upload
     *    ->
     * COMPLETED / FAILED
     *
     * This will be removed once Kafka + worker-service
     * handle asynchronous processing (Phase 7).
     */
    @Transactional
    public Job processJobManually(UUID userId, UUID jobId) {
        Job job = getJob(userId, jobId);

        if (job.getStatus() != JobStatus.QUEUED) {
            throw new JobNotProcessableException(job.getStatus().name());
        }

        job.setStatus(JobStatus.PROCESSING);
        job.setStartedAt(LocalDateTime.now());
        jobRepository.save(job);
        recordEvent(job.getId(), "PROCESSING", Map.of());

        try {
            AiGenerationResult result = aiProvider.generate(
                    new AiGenerationRequest(job.getPrompt(), job.getModel(), job.getParameters()));

            String key = "jobs/" + job.getId() + "/result.png";
            String resultUrl = objectStorage.upload(key, result.data(), result.contentType());

            job.setStatus(JobStatus.COMPLETED);
            job.setResultUrl(resultUrl);
            job.setCompletedAt(LocalDateTime.now());
            Job saved = jobRepository.save(job);
            recordEvent(saved.getId(), "COMPLETED", Map.of("resultUrl", resultUrl));
            return saved;

        } catch (Exception ex) {
            job.setStatus(JobStatus.FAILED);
            job.setErrorCode("INTERNAL_ERROR");
            job.setErrorMessage(ex.getMessage());
            job.setCompletedAt(LocalDateTime.now());
            Job saved = jobRepository.save(job);
            recordEvent(saved.getId(), "FAILED", Map.of(
                    "errorMessage", String.valueOf(ex.getMessage())
            ));
            return saved;
        }
    }

    private JobPriority parsePriority(String rawPriority) {
        if (rawPriority == null || rawPriority.isBlank()) {
            return JobPriority.NORMAL;
        }
        try {
            return JobPriority.valueOf(rawPriority.toUpperCase());
        } catch (IllegalArgumentException ex) {
            return JobPriority.NORMAL;
        }
    }

    private void recordEvent(UUID jobId, String eventType, Map<String, Object> metadata) {
        JobEvent event = new JobEvent();
        event.setJobId(jobId);
        event.setEventType(eventType);
        event.setMetadata(metadata);
        jobEventRepository.save(event);
    }
}