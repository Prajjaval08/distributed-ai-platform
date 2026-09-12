package com.distributedai.api.kafka;

import com.distributedai.api.entity.JobPriority;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class JobProducer {

    private final KafkaTemplate<String, JobMessage> kafkaTemplate;

    private final String highTopic;
    private final String normalTopic;
    private final String lowTopic;

    private final Tracer tracer =
            GlobalOpenTelemetry.getTracer("api-service-kafka-producer");

    private static final TextMapSetter<ProducerRecord<String, JobMessage>> SETTER =
            new TextMapSetter<>() {

                @Override
                public void set(
                        ProducerRecord<String, JobMessage> record,
                        String key,
                        String value) {

                    if (value != null) {
                        record.headers().add(
                                key,
                                value.getBytes(StandardCharsets.UTF_8)
                        );
                    }
                }
            };

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

        Span span = tracer.spanBuilder("kafka send " + topic)
                .setSpanKind(SpanKind.PRODUCER)
                .startSpan();

        try (var scope = span.makeCurrent()) {

            ProducerRecord<String, JobMessage> record =
                    new ProducerRecord<>(
                            topic,
                            message.jobId().toString(),
                            message
                    );

            GlobalOpenTelemetry.getPropagators()
                    .getTextMapPropagator()
                    .inject(
                            Context.current(),
                            record,
                            SETTER
                    );

            SendResult<String, JobMessage> result =
                    kafkaTemplate
                            .send(record)
                            .get(5, TimeUnit.SECONDS);

        } catch (ExecutionException ex) {

            span.recordException(ex);

            throw new JobPublishException(
                    "Failed to publish job to Kafka",
                    ex.getCause()
            );

        } catch (InterruptedException ex) {

            span.recordException(ex);

            Thread.currentThread().interrupt();

            throw new JobPublishException(
                    "Interrupted while publishing job to Kafka",
                    ex
            );

        } catch (TimeoutException ex) {

            span.recordException(ex);

            throw new JobPublishException(
                    "Timed out publishing job to Kafka",
                    ex
            );

        } finally {

            span.end();
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