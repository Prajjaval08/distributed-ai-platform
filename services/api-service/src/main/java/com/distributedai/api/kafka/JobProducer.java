package com.distributedai.api.kafka;

import com.distributedai.api.entity.JobPriority;
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

    private final String highTopic;
    private final String normalTopic;
    private final String lowTopic;

    public JobProducer(
            KafkaTemplate<String, JobMessage> kafkaTemplate,
            @Value("${kafka-topics.ai-jobs-high}") String highTopic,
            @Value("${kafka-topics.ai-jobs}") String normalTopic,
            @Value("${kafka-topics.ai-jobs-low}") String lowTopic
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.highTopic = highTopic;
        this.normalTopic = normalTopic;
        this.lowTopic = lowTopic;
    }

    public void publish(JobMessage message) {
        String topic = resolveTopic(message.priority());

        try {
            SendResult<String, JobMessage> result = kafkaTemplate
                    .send(topic, message.jobId().toString(), message)
                    .get(5, TimeUnit.SECONDS);

        } catch (ExecutionException ex) {
            throw new JobPublishException(
                    "Failed to publish job to Kafka",
                    ex.getCause()
            );

        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new JobPublishException(
                    "Interrupted while publishing job to Kafka",
                    ex
            );

        } catch (TimeoutException ex) {
            throw new JobPublishException(
                    "Timed out publishing job to Kafka",
                    ex
            );
        }
    }

    private String resolveTopic(String priority) {
        if (JobPriority.HIGH.name().equals(priority)) {
            return highTopic;
        }

        if (JobPriority.LOW.name().equals(priority)) {
            return lowTopic;
        }

        return normalTopic;
    }
}