package com.distributedai.worker.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class DlqProducer {

    private final KafkaTemplate<String, DlqMessage> kafkaTemplate;
    private final String topic;

    public DlqProducer(
            KafkaTemplate<String, DlqMessage> kafkaTemplate,
            @Value("${kafka-topics.ai-jobs-dlq}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(DlqMessage message) {
        try {
            kafkaTemplate.send(topic, message.jobId().toString(), message).get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            // If even the DLQ publish fails, we don't want to crash the
            // consumer over a secondary concern - the job's FAILED status
            // in PostgreSQL remains the durable record either way.
            throw new RuntimeException("Failed to publish to DLQ", ex);
        }
    }
}