package com.distributedai.worker.service;

import com.distributedai.worker.ai.AiGenerationRequest;
import com.distributedai.worker.ai.AiGenerationResult;
import com.distributedai.worker.ai.AiProvider;
import com.distributedai.worker.entity.Job;
import com.distributedai.worker.entity.JobEvent;
import com.distributedai.worker.entity.JobStatus;
import com.distributedai.worker.kafka.DlqMessage;
import com.distributedai.worker.kafka.DlqProducer;
import com.distributedai.worker.kafka.JobMessage;
import com.distributedai.worker.repository.JobEventRepository;
import com.distributedai.worker.repository.JobRepository;
import com.distributedai.worker.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private final DlqProducer dlqProducer;
    private final BackoffCalculator backoffCalculator;
    private final int maxAttempts;

    public JobProcessingService(
            JobRepository jobRepository,
            JobEventRepository jobEventRepository,
            AiProvider aiProvider,
            ObjectStorage objectStorage,
            DlqProducer dlqProducer,
            BackoffCalculator backoffCalculator,
            @Value("${retry.max-attempts}") int maxAttempts
    ) {
        this.jobRepository = jobRepository;
        this.jobEventRepository = jobEventRepository;
        this.aiProvider = aiProvider;
        this.objectStorage = objectStorage;
        this.dlqProducer = dlqProducer;
        this.backoffCalculator = backoffCalculator;
        this.maxAttempts = maxAttempts;
    }

    public void process(JobMessage message) {
        Optional<Job> maybeJob = jobRepository.findById(message.jobId());
        if (maybeJob.isEmpty()) {
            log.warn("Received message for unknown jobId={}, skipping", message.jobId());
            return;
        }

        Job job = maybeJob.get();
        if (job.getStatus() != JobStatus.QUEUED) {
            log.info("Job {} is not QUEUED (status={}), skipping - likely a redelivery", job.getId(), job.getStatus());
            return;
        }

        markProcessing(job);
        attemptWithRetries(job, message);
    }

    private void attemptWithRetries(Job job, JobMessage message) {
        int attempt = 0;

        while (true) {
            attempt++;
            try {
                AiGenerationResult result = aiProvider.generate(
                        new AiGenerationRequest(job.getPrompt(), job.getModel(), job.getParameters()));

                String key = "jobs/" + job.getId() + "/result.png";
                String resultUrl = objectStorage.upload(key, result.data(), result.contentType());

                markCompleted(job, resultUrl);
                log.info("Job {} completed successfully on attempt {}", job.getId(), attempt);
                return;

            } catch (Exception ex) {
                FailureCategory category = FailureClassifier.classify(ex);
                log.warn("Job {} failed on attempt {} ({}): {}", job.getId(), attempt, category, ex.getMessage());

                boolean retryable = FailureClassifier.isRetryable(category);
                boolean attemptsRemain = attempt < maxAttempts;

                if (retryable && attemptsRemain) {
                    incrementRetryCount(job);
                    long delay = backoffCalculator.delayMillis(attempt);
                    recordEvent(job.getId(), "RETRY", Map.of(
                            "attempt", attempt,
                            "category", category.name(),
                            "delayMs", delay,
                            "errorMessage", String.valueOf(ex.getMessage())
                    ));
                    sleep(delay);
                    continue; // retry
                }

                // Not retryable, or retries exhausted: terminal FAILED + DLQ
                markFailed(job, category, ex.getMessage());
                publishToDlq(job, message, category, ex.getMessage());
                return;
            }
        }
    }

    private void markProcessing(Job job) {
        job.setStatus(JobStatus.PROCESSING);
        job.setStartedAt(LocalDateTime.now());
        jobRepository.save(job);
        recordEvent(job.getId(), "PROCESSING", Map.of());
    }

    private void markCompleted(Job job, String resultUrl) {
        job.setStatus(JobStatus.COMPLETED);
        job.setResultUrl(resultUrl);
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);
        recordEvent(job.getId(), "COMPLETED", Map.of("resultUrl", resultUrl));
    }

    private void markFailed(Job job, FailureCategory category, String errorMessage) {
        job.setStatus(JobStatus.FAILED);
        job.setErrorCode(category.name());
        job.setErrorMessage(errorMessage);
        job.setCompletedAt(LocalDateTime.now());
        jobRepository.save(job);
        recordEvent(job.getId(), "FAILED", Map.of(
                "category", category.name(),
                "errorMessage", String.valueOf(errorMessage),
                "retryCount", job.getRetryCount()
        ));
        log.error("Job {} FAILED permanently ({}), retryCount={}: {}",
                job.getId(), category, job.getRetryCount(), errorMessage);
    }

    private void incrementRetryCount(Job job) {
        job.setRetryCount(job.getRetryCount() + 1);
        jobRepository.save(job);
    }

    private void publishToDlq(Job job, JobMessage message, FailureCategory category, String errorMessage) {
        try {
            dlqProducer.publish(new DlqMessage(
                    job.getId(),
                    message.userId(),
                    job.getPrompt(),
                    job.getModel(),
                    category.name(),
                    job.getRetryCount(),
                    String.valueOf(errorMessage)
            ));
            recordEvent(job.getId(), "DLQ", Map.of("category", category.name()));
        } catch (Exception dlqEx) {
            // Job is already durably FAILED in PostgreSQL - a DLQ publish
            // failure is logged but does not change the job's outcome.
            log.error("Failed to publish job {} to DLQ: {}", job.getId(), dlqEx.getMessage());
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    @Transactional
    protected void recordEvent(UUID jobId, String eventType, Map<String, Object> metadata) {
        JobEvent event = new JobEvent();
        event.setJobId(jobId);
        event.setEventType(eventType);
        event.setMetadata(metadata);
        jobEventRepository.save(event);
    }
}