package com.distributedai.api.service;

import com.distributedai.api.dto.CreateJobRequest;
import com.distributedai.api.entity.Job;
import com.distributedai.api.entity.JobPriority;
import com.distributedai.api.entity.JobStatus;
import com.distributedai.api.exception.JobNotCancellableException;
import com.distributedai.api.exception.JobNotFoundException;
import com.distributedai.api.kafka.JobProducer;
import com.distributedai.api.repository.IdempotencyKeyRepository;
import com.distributedai.api.repository.JobEventRepository;
import com.distributedai.api.repository.JobRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobEventRepository jobEventRepository;

    @Mock
    private JobProducer jobProducer;

    @Mock
    private JobCacheService jobCacheService;

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Mock
    private MeterRegistry meterRegistry;

    @Mock
    private Counter counter;

    @Mock
    private com.distributedai.api.config.BusinessMetrics businessMetrics;

    @InjectMocks
    private JobService jobService;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
    }

    @Test
    void createJob_savesJobAndPublishesToKafka() {

        when(jobRepository.save(any(Job.class))).thenAnswer(inv -> {
            Job job = inv.getArgument(0);

            if (job.getId() == null) {
                job.setId(UUID.randomUUID());
            }

            return job;
        });

        when(meterRegistry.counter(
                eq("jobs.created"),
                eq("priority"),
                eq("NORMAL")
        )).thenReturn(counter);

        CreateJobRequest request =
                new CreateJobRequest(
                        "a prompt",
                        "model-v1",
                        "NORMAL",
                        null
                );

        TransactionSynchronizationManager.initSynchronization();

        try {
            Job result = jobService.createJob(
                    userId,
                    request,
                    null
            );

            assertThat(result.getStatus())
                    .isEqualTo(JobStatus.QUEUED);

            verify(jobRepository, times(2))
                    .save(any(Job.class));

            TransactionSynchronization synchronization =
                    TransactionSynchronizationManager
                            .getSynchronizations()
                            .get(0);

            synchronization.afterCommit();

            verify(jobProducer, times(1))
                    .publish(any());

            verify(counter, times(1))
                    .increment();

            verify(businessMetrics, times(1))
                    .incrementJobsCreated("NORMAL");

        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createJob_withExistingIdempotencyKey_returnsOriginalJobWithoutPublishing() {

        UUID existingJobId = UUID.randomUUID();

        Job existingJob = new Job();
        existingJob.setId(existingJobId);
        existingJob.setStatus(JobStatus.QUEUED);

        var existingKey =
                new com.distributedai.api.entity.IdempotencyKey();

        existingKey.setJobId(existingJobId);

        when(idempotencyKeyRepository.findByKeyAndUserId(
                "key-123",
                userId
        )).thenReturn(Optional.of(existingKey));

        when(jobRepository.findById(existingJobId))
                .thenReturn(Optional.of(existingJob));

        CreateJobRequest request =
                new CreateJobRequest(
                        "a prompt",
                        "model-v1",
                        "NORMAL",
                        null
                );

        Job result =
                jobService.createJob(
                        userId,
                        request,
                        "key-123"
                );

        assertThat(result.getId())
                .isEqualTo(existingJobId);

        verifyNoInteractions(jobProducer);

        verify(jobRepository, never())
                .save(any());
    }

    @Test
    void getJob_forNonExistentJob_throwsJobNotFoundException() {

        UUID jobId = UUID.randomUUID();

        when(jobRepository.findByIdAndUserId(
                jobId,
                userId
        )).thenReturn(Optional.empty());

        assertThatThrownBy(
                () -> jobService.getJob(userId, jobId)
        ).isInstanceOf(JobNotFoundException.class);
    }

    @Test
    void cancelJob_fromQueuedStatus_succeeds() {

        UUID jobId = UUID.randomUUID();

        Job job = new Job();

        job.setId(jobId);
        job.setUserId(userId);
        job.setStatus(JobStatus.QUEUED);
        job.setPriority(JobPriority.NORMAL);

        when(jobRepository.findByIdAndUserId(
                jobId,
                userId
        )).thenReturn(Optional.of(job));

        when(jobRepository.save(any(Job.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Job result =
                jobService.cancelJob(
                        userId,
                        jobId
                );

        assertThat(result.getStatus())
                .isEqualTo(JobStatus.CANCELLED);
    }

    @Test
    void cancelJob_fromCompletedStatus_throwsJobNotCancellableException() {

        UUID jobId = UUID.randomUUID();

        Job job = new Job();

        job.setId(jobId);
        job.setUserId(userId);
        job.setStatus(JobStatus.COMPLETED);

        when(jobRepository.findByIdAndUserId(
                jobId,
                userId
        )).thenReturn(Optional.of(job));

        assertThatThrownBy(
                () -> jobService.cancelJob(
                        userId,
                        jobId
                )
        ).isInstanceOf(JobNotCancellableException.class);
    }
}
