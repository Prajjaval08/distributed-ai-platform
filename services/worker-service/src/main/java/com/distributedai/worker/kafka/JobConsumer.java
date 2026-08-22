package com.distributedai.worker.kafka;

import com.distributedai.worker.service.JobProcessingService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class JobConsumer {

    private final JobProcessingService jobProcessingService;

    public JobConsumer(JobProcessingService jobProcessingService) {
        this.jobProcessingService = jobProcessingService;
    }

    /**
     * Consumer group ai-worker-group: all worker instances share this group
     * so Kafka distributes ai-jobs partitions across them (Section 12.3).
     * Manual ack mode - see application.yml (enable-auto-commit: false).
     * The offset is only committed after jobProcessingService has durably
     * persisted the job's terminal state, so a crash mid-processing results
     * in safe redelivery, not lost work (Section 13).
     */
    @KafkaListener(
            topics = "${kafka-topics.ai-jobs}",
            groupId = "ai-worker-group",
            containerFactory = "jobKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, JobMessage> record, Acknowledgment acknowledgment) {
        JobMessage message = record.value();
        jobProcessingService.process(message);
        acknowledgment.acknowledge();
    }
}
