package com.distributedai.worker.kafka;

import com.distributedai.worker.service.JobProcessingService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class PriorityJobPoller implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(PriorityJobPoller.class);

    private final JobProcessingService jobProcessingService;
    private final String bootstrapServers;
    private final String highTopic;
    private final String normalTopic;
    private final String lowTopic;

    private final Tracer tracer =
            GlobalOpenTelemetry.getTracer("worker-service-kafka-consumer");

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor();

    private KafkaConsumer<String, JobMessage> highConsumer;
    private KafkaConsumer<String, JobMessage> normalConsumer;
    private KafkaConsumer<String, JobMessage> lowConsumer;

    private static final TextMapGetter<ConsumerRecord<String, JobMessage>> GETTER =
            new TextMapGetter<>() {

                @Override
                public Iterable<String> keys(
                        ConsumerRecord<String, JobMessage> record) {

                    return java.util.stream.StreamSupport
                            .stream(record.headers().spliterator(), false)
                            .map(Header::key)
                            .toList();
                }

                @Override
                public String get(
                        ConsumerRecord<String, JobMessage> record,
                        String key) {

                    Header header = record.headers().lastHeader(key);

                    return header == null
                            ? null
                            : new String(
                                    header.value(),
                                    java.nio.charset.StandardCharsets.UTF_8
                            );
                }
            };

    public PriorityJobPoller(
            JobProcessingService jobProcessingService,
            @Value("${spring.kafka.bootstrap-servers}") String bootstrapServers,
            @Value("${kafka-topics.ai-jobs-high}") String highTopic,
            @Value("${kafka-topics.ai-jobs}") String normalTopic,
            @Value("${kafka-topics.ai-jobs-low}") String lowTopic
    ) {
        this.jobProcessingService = jobProcessingService;
        this.bootstrapServers = bootstrapServers;
        this.highTopic = highTopic;
        this.normalTopic = normalTopic;
        this.lowTopic = lowTopic;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {

        highConsumer = buildConsumer("ai-worker-group-high");
        highConsumer.subscribe(List.of(highTopic));

        normalConsumer = buildConsumer("ai-worker-group");
        normalConsumer.subscribe(List.of(normalTopic));

        lowConsumer = buildConsumer("ai-worker-group-low");
        lowConsumer.subscribe(List.of(lowTopic));

        executor.scheduleWithFixedDelay(
                this::pollCycle,
                0,
                500,
                TimeUnit.MILLISECONDS
        );

        log.info(
                "PriorityJobPoller started - polling {} (high), {} (normal), {} (low) in strict precedence",
                highTopic,
                normalTopic,
                lowTopic
        );
    }

    private void pollCycle() {

        if (!running.get()) {
            return;
        }

        try {

            if (drain(highConsumer, "HIGH")) {
                return;
            }

            if (drain(normalConsumer, "NORMAL")) {
                return;
            }

            drain(lowConsumer, "LOW");

        } catch (Exception ex) {

            log.error(
                    "Error during priority poll cycle: {}",
                    ex.getMessage(),
                    ex
            );
        }
    }

    private boolean drain(
            KafkaConsumer<String, JobMessage> consumer,
            String label) {

        ConsumerRecords<String, JobMessage> records =
                consumer.poll(Duration.ofMillis(200));

        if (records.isEmpty()) {
            return false;
        }

        for (ConsumerRecord<String, JobMessage> record : records) {

            Context extractedContext =
                    GlobalOpenTelemetry.getPropagators()
                            .getTextMapPropagator()
                            .extract(
                                    Context.current(),
                                    record,
                                    GETTER
                            );

            Span span = tracer.spanBuilder(
                            "process " + label + " job"
                    )
                    .setParent(extractedContext)
                    .setSpanKind(SpanKind.CONSUMER)
                    .startSpan();

            try (var scope = span.makeCurrent()) {

                jobProcessingService.process(record.value());

            } catch (Exception ex) {

                span.recordException(ex);

                log.error(
                        "Unhandled error processing {} priority job: {}",
                        label,
                        ex.getMessage(),
                        ex
                );

            } finally {

                span.end();
            }
        }

        consumer.commitSync();

        return true;
    }

    private KafkaConsumer<String, JobMessage> buildConsumer(
            String groupId) {

        Properties props = new Properties();

        props.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers
        );

        props.put(
                ConsumerConfig.GROUP_ID_CONFIG,
                groupId
        );

        props.put(
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class
        );

        props.put(
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                ErrorHandlingDeserializer.class
        );

        props.put(
                ErrorHandlingDeserializer.VALUE_DESERIALIZER_CLASS,
                JsonDeserializer.class
        );

        props.put(
                JsonDeserializer.TRUSTED_PACKAGES,
                "com.distributedai.worker.kafka"
        );

        props.put(
                JsonDeserializer.VALUE_DEFAULT_TYPE,
                "com.distributedai.worker.kafka.JobMessage"
        );

        props.put(
                JsonDeserializer.USE_TYPE_INFO_HEADERS,
                false
        );

        props.put(
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false
        );

        props.put(
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest"
        );

        return new KafkaConsumer<>(props);
    }

    @Override
    public void destroy() {

        running.set(false);

        executor.shutdown();

        try {

            executor.awaitTermination(
                    5,
                    TimeUnit.SECONDS
            );

        } catch (InterruptedException ignored) {

            Thread.currentThread().interrupt();
        }

        if (highConsumer != null) {
            highConsumer.close();
        }

        if (normalConsumer != null) {
            normalConsumer.close();
        }

        if (lowConsumer != null) {
            lowConsumer.close();
        }

        log.info("PriorityJobPoller stopped");
    }
}