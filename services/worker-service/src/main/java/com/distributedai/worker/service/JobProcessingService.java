package com.distributedai.worker.service;

import com.distributedai.worker.ai.AiGenerationRequest;
import com.distributedai.worker.ai.AiGenerationResult;
import com.distributedai.worker.ai.AiProvider;
import com.distributedai.worker.entity.Job;
import com.distributedai.worker.entity.JobEvent;
import com.distributedai.worker.entity.JobStatus;
import com.distributedai.worker.kafka.JobMessage;
import com.distributedai.worker.repository.JobEventRepository;
import com.distributedai.worker.repository.JobRepository;
import com.distributedai.worker.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobProcessingService {

    private static final Logger log = LoggerFactory.getLogger(JobProcessingService.class);

    private final JobRepository jobRepository;
    private final JobEventRepository jobEventRepository;
    private final AiProvider aiProvider;
    private final ObjectStorage objectStorage;

    public JobProcessingService(
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
    public void process(JobMessage message) {
        Optional<Job> maybeJob = jobRepository.findById(message.jobId());

        if (maybeJob.isEmpty()) {
            // Job row missing entirely - log and move on rather than crash
            // the consumer. Real classification/DLQ handling is Phase 8.
            log.warn("Received message for unknown jobId={}, skipping", message.jobId());
            return;
        }

        Job job = maybeJob.get();

        // Job-state guard (Section 13): only act on QUEUED jobs. This is
        // what makes Kafka's at-least-once redelivery safe - a redelivered
        // message for an already-PROCESSING/COMPLETED job is a no-op here,
        // not a duplicate side effect.
        if (job.getStatus() != JobStatus.QUEUED) {
            log.info("Job {} is not QUEUED (status={}), skipping - likely a redelivery", job.getId(), job.getStatus());
            return;
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
            jobRepository.save(job);
            recordEvent(job.getId(), "COMPLETED", Map.of("resultUrl", resultUrl));

            log.info("Job {} completed successfully", job.getId());

        } catch (Exception ex) {
            // No retry/DLQ classification yet - Phase 8. Any failure is
            // currently terminal.
            job.setStatus(JobStatus.FAILED);
            job.setErrorCode("INTERNAL_ERROR");
            job.setErrorMessage(ex.getMessage());
            job.setCompletedAt(LocalDateTime.now());
            jobRepository.save(job);
            recordEvent(job.getId(), "FAILED", Map.of("errorMessage", String.valueOf(ex.getMessage())));

            log.error("Job {} failed: {}", job.getId(), ex.getMessage());
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
