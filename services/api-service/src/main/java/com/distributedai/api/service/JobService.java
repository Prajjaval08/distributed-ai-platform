package com.distributedai.api.service;

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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import com.distributedai.api.config.DataSourceContextHolder;
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

    public JobService(
            JobRepository jobRepository,
            JobEventRepository jobEventRepository,
            JobProducer jobProducer,
            JobCacheService jobCacheService,
            IdempotencyKeyRepository idempotencyKeyRepository
    ) {
        this.jobRepository = jobRepository;
        this.jobEventRepository = jobEventRepository;
        this.jobProducer = jobProducer;
        this.jobCacheService = jobCacheService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
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

        if (job.getStatus() != JobStatus.CREATED && job.getStatus() != JobStatus.QUEUED) {
            throw new JobNotCancellableException(job.getStatus().name());
        }

        String previousStatus = job.getStatus().name();
        job.setStatus(JobStatus.CANCELLED);
        Job saved = jobRepository.save(job);
        recordEvent(saved.getId(), "CANCELLED", Map.of("previousStatus", previousStatus));

        jobCacheService.put(userId, JobResponse.from(saved));

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