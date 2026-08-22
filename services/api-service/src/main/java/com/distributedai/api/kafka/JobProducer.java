package com.distributedai.api.kafka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class JobProducer {

    private final KafkaTemplate<String, JobMessage> kafkaTemplate;
    private final String topic;

    public JobProducer(
            KafkaTemplate<String, JobMessage> kafkaTemplate,
            @Value("${kafka-topics.ai-jobs}") String topic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    public void publish(JobMessage message) {
        try {
            SendResult<String, JobMessage> result = kafkaTemplate
                    .send(topic, message.jobId().toString(), message)
                    .get(5, TimeUnit.SECONDS);

        } catch (ExecutionException ex) {
            throw new JobPublishException("Failed to publish job to Kafka", ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new JobPublishException("Interrupted while publishing job to Kafka", ex);
        } catch (TimeoutException ex) {
            throw new JobPublishException("Timed out publishing job to Kafka", ex);
        }
    }
}