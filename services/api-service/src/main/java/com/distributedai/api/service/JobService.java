package com.distributedai.api.service;

import com.distributedai.api.ai.AiProvider;
import com.distributedai.api.dto.CreateJobRequest;
import com.distributedai.api.entity.Job;
import com.distributedai.api.entity.JobEvent;
import com.distributedai.api.entity.JobPriority;
import com.distributedai.api.entity.JobStatus;
import com.distributedai.api.exception.JobNotCancellableException;
import com.distributedai.api.exception.JobNotFoundException;
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

        Job saved = jobRepository.save(job);

        recordEvent(saved.getId(), "CREATED", Map.of(
                "model", saved.getModel(),
                "priority", saved.getPriority().name()
        ));

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

        // Do not cache newly created/queued jobs.
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

        if (job.getStatus() != JobStatus.CREATED
                && job.getStatus() != JobStatus.QUEUED) {
            throw new JobNotCancellableException(job.getStatus().name());
        }

        String previousStatus = job.getStatus().name();

        job.setStatus(JobStatus.CANCELLED);
        Job saved = jobRepository.save(job);

        recordEvent(saved.getId(), "CANCELLED", Map.of(
                "previousStatus", previousStatus
        ));

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