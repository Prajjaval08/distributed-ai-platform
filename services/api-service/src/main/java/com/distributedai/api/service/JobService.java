package com.distributedai.api.service;

import com.distributedai.api.config.DataSourceContextHolder;
import com.distributedai.api.config.BusinessMetrics;
import com.distributedai.api.dto.CreateJobRequest;
import com.distributedai.api.dto.JobResponse;
import com.distributedai.api.entity.IdempotencyKey;
import com.distributedai.api.entity.Job;
import com.distributedai.api.entity.JobEvent;
import com.distributedai.api.entity.JobPriority;
import com.distributedai.api.entity.JobStatus;
import com.distributedai.api.exception.DuplicateIdempotencyKeyException;
import com.distributedai.api.exception.JobNotCancellableException;
import com.distributedai.api.exception.JobNotFoundException;
import com.distributedai.api.exception.JobResultNotReadyException;
import com.distributedai.api.kafka.JobMessage;
import com.distributedai.api.kafka.JobProducer;
import com.distributedai.api.repository.IdempotencyKeyRepository;
import com.distributedai.api.repository.JobEventRepository;
import com.distributedai.api.repository.JobRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final JobEventRepository jobEventRepository;
    private final JobProducer jobProducer;
    private final JobCacheService jobCacheService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final MeterRegistry meterRegistry;
    private final BusinessMetrics businessMetrics;

    public JobService(
            JobRepository jobRepository,
            JobEventRepository jobEventRepository,
            JobProducer jobProducer,
            JobCacheService jobCacheService,
            IdempotencyKeyRepository idempotencyKeyRepository,
            MeterRegistry meterRegistry,
            BusinessMetrics businessMetrics
    ) {
        this.jobRepository = jobRepository;
        this.jobEventRepository = jobEventRepository;
        this.jobProducer = jobProducer;
        this.jobCacheService = jobCacheService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.meterRegistry = meterRegistry;
        this.businessMetrics = businessMetrics;
    }

    @Transactional
    public Job createJob(UUID userId, CreateJobRequest request, String idempotencyKey) {

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<IdempotencyKey> existing =
                    idempotencyKeyRepository.findByKeyAndUserId(idempotencyKey, userId);

            if (existing.isPresent()) {
                return jobRepository.findById(existing.get().getJobId())
                        .orElseThrow(JobNotFoundException::new);
            }
        }

        Job job = new Job();
        job.setUserId(userId);
        job.setPrompt(request.prompt());
        job.setModel(request.model());
        job.setParameters(request.parameters());
        job.setPriority(parsePriority(request.priority()));

        Job saved = jobRepository.save(job);

        recordEvent(saved.getId(), "CREATED", Map.of(
                "model", saved.getModel(),
                "priority", saved.getPriority().name()
        ));

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            try {
                IdempotencyKey record = new IdempotencyKey();
                record.setKey(idempotencyKey);
                record.setUserId(userId);
                record.setJobId(saved.getId());
                record.setExpiresAt(LocalDateTime.now().plusHours(24));

                idempotencyKeyRepository.save(record);

            } catch (DataIntegrityViolationException dup) {
                throw new DuplicateIdempotencyKeyException(saved.getId());
            }
        }

        saved.setStatus(JobStatus.QUEUED);

        Job queued = jobRepository.save(saved);

        recordEvent(queued.getId(), "QUEUED", Map.of());

        publishAfterCommit(queued);

        meterRegistry.counter(
                "jobs.created",
                "priority",
                queued.getPriority().name()
        ).increment();
        businessMetrics.incrementJobsCreated(queued.getPriority().name());

        return queued;
    }

    private void publishAfterCommit(Job job) {
        JobMessage message = new JobMessage(
                job.getId(),
                job.getUserId(),
                job.getPrompt(),
                job.getModel(),
                job.getPriority().name(),
                job.getParameters()
        );

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                jobProducer.publish(message);
            }
        });
    }

    @Transactional(readOnly = true)
    public Job getJob(UUID userId, UUID jobId) {
        return jobRepository.findByIdAndUserId(jobId, userId)
                .orElseThrow(JobNotFoundException::new);
    }

    @Transactional(readOnly = true)
    public Page<Job> listJobs(UUID userId, Pageable pageable) {
        try {
            DataSourceContextHolder.useReplica();
            return jobRepository.findByUserId(userId, pageable);
        } finally {
            DataSourceContextHolder.clear();
        }
    }

    @Transactional
    public Job cancelJob(UUID userId, UUID jobId) {

        Job job = getJob(userId, jobId);

        if (job.getStatus() != JobStatus.CREATED
                && job.getStatus() != JobStatus.QUEUED) {

            throw new JobNotCancellableException(
                    job.getStatus().name()
            );
        }

        String previousStatus = job.getStatus().name();

        job.setStatus(JobStatus.CANCELLED);

        Job saved = jobRepository.save(job);

        recordEvent(
                saved.getId(),
                "CANCELLED",
                Map.of("previousStatus", previousStatus)
        );

        jobCacheService.put(
                userId,
                JobResponse.from(saved)
        );

        return saved;
    }

    @Transactional(readOnly = true)
    public Job getResult(UUID userId, UUID jobId) {

        Job job = getJob(userId, jobId);

        if (job.getStatus() != JobStatus.COMPLETED) {
            throw new JobResultNotReadyException(
                    job.getStatus().name()
            );
        }

        return job;
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
