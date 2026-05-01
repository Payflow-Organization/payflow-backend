package com.payflow.infrastructure.kafka;

import com.payflow.domain.model.outbox.OutboxEvent;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class TransactionEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Tracer tracer;

    @Value("${payflow.kafka.topics.transactions}")
    private String transactionsTopic;

    public void publish(OutboxEvent event) {
        ProducerRecord<String, String> kafkaRecord = new ProducerRecord<>(
                transactionsTopic,
                event.getAggregateId().toString(),
                event.getPayload()
        );

        Span currentSpan = tracer.currentSpan();
        if (currentSpan != null) {
            String traceparent = "00-" + currentSpan.context().traceId()
                    + "-" + currentSpan.context().spanId()
                    + "-01";
            kafkaRecord.headers().add("traceparent", traceparent.getBytes(StandardCharsets.UTF_8));
        }

        kafkaTemplate.send(kafkaRecord).join();
    }
}