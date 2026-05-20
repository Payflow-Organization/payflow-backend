package com.payflow.infrastructure.kafka.consumer;

import org.slf4j.MDC;
import org.springframework.messaging.handler.annotation.Header;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.payflow.domain.model.audit.AuditLog;
import com.payflow.domain.model.event.ProcessedEvent;
import com.payflow.infrastructure.persistence.jpa.AuditLogRepository;
import com.payflow.infrastructure.persistence.jpa.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static com.payflow.infrastructure.kafka.TransactionOutboxWriter.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditConsumer {
    private final ProcessedEventRepository processedEventRepository;
    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;
    private static final String CONSUMER_GROUP = "audit";

    @KafkaListener(
            topics = "${payflow.kafka.topics.transactions}",
            groupId = "${payflow.kafka.consumer.audit-group}"
    )
    @Transactional
    public void handle(String payload,
                       @Header(value = "traceparent", required = false) String traceparent) {
        if (traceparent != null) {
            String[] parts = traceparent.split("-");
            if (parts.length == 4) {
                MDC.put("trace.id", parts[1]);
                MDC.put("span.id", parts[2]);
            }
        }
        try {
            TransactionCreatedPayload event = objectMapper.readValue(payload, TransactionCreatedPayload.class);
            if (processedEventRepository.existsByIdEventIdAndIdConsumerGroup(event.transactionId(), CONSUMER_GROUP)) { // ADR-012: idempotency via processed_events, consumer_group discriminator allows independent consumers on same topic
                log.warn("Duplicate audit event skipped  txId={}",
                         event.transactionId());
                return;
            }

            auditLogRepository.save(AuditLog.builder()
                    .action(event.type().name())
                    .entityType("Transaction")
                    .userId(event.userId())
                    .entityId(event.transactionId())
                    .build());

              processedEventRepository.save(ProcessedEvent.builder()
                    .id(ProcessedEvent.ProcessedEventId.builder()
                            .eventId(event.transactionId())
                            .consumerGroup(CONSUMER_GROUP)
                            .build())
                    .processedAt(Instant.now())
                    .build());
            log.info("Audit event processed txId={} type={}", event.transactionId(), event.type().name());

        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to deserialize payload", e);
        }
        finally {
            MDC.clear();
        }
    }
}
