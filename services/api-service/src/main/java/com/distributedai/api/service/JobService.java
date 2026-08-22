package com.distributedai.api.service;

import com.distributedai.api.dto.CreateJobRequest;
import com.distributedai.api.entity.Job;
import com.distributedai.api.entity.JobEvent;
import com.distributedai.api.entity.JobPriority;
import com.distributedai.api.entity.JobStatus;
import com.distributedai.api.exception.JobNotCancellableException;
import com.distributedai.api.exception.JobNotFoundException;
import com.distributedai.api.exception.JobResultNotReadyException;
import com.distributedai.api.repository.JobEventRepository;
import com.distributedai.api.repository.JobRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobRepository;
    private final JobEventRepository jobEventRepository;

    public JobService(JobRepository jobRepository, JobEventRepository jobEventRepository) {
        this.jobRepository = jobRepository;
        this.jobEventRepository = jobEventRepository;
    }

    @Transactional
    public Job createJob(UUID userId, CreateJobRequest request) {
        Job job = new Job();
        job.setUserId(userId);
        job.setPrompt(request.prompt());
        job.setModel(request.model());
        job.setParameters(request.parameters());
        job.setPriority(parsePriority(request.priority()));
        // status defaults to CREATED via @PrePersist (Phase 2).
        // NOTE: does not transition to QUEUED here - that requires the Kafka
        // producer introduced in Phase 6. A job sits at CREATED until then.

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

        // Only CREATED is cancellable right now. Phase 6 will extend this
        // once QUEUED becomes a reachable state.
        if (job.getStatus() != JobStatus.CREATED) {
            throw new JobNotCancellableException(job.getStatus().name());
        }

        job.setStatus(JobStatus.CANCELLED);
        Job saved = jobRepository.save(job);
        recordEvent(saved.getId(), "CANCELLED", Map.of("previousStatus", "CREATED"));

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
            // Invalid priority string falls back to NORMAL rather than
            // rejecting the whole request - a deliberately forgiving choice
            // for a non-critical field. Worth reconsidering if this needs
            // to be strict later (would become a 400 INVALID_REQUEST instead).
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