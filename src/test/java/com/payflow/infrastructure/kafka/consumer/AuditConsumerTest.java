package com.payflow.infrastructure.kafka.consumer;

import tools.jackson.databind.ObjectMapper;
import com.payflow.domain.model.audit.AuditLog;
import com.payflow.domain.model.event.ProcessedEvent;
import com.payflow.infrastructure.persistence.jpa.AuditLogRepository;
import com.payflow.infrastructure.persistence.jpa.ProcessedEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditConsumerTest {

    @Mock private ProcessedEventRepository processedEventRepository;
    @Mock private AuditLogRepository auditLogRepository;

    private AuditConsumer consumer;

    private static final UUID EVENT_ID  = UUID.randomUUID();
    private static final UUID WALLET_ID = UUID.randomUUID();
    private static final String AUDIT_GROUP = "audit";

    private String validPayload;

    @BeforeEach
    void setUp() {
        ObjectMapper objectMapper = new ObjectMapper();
        consumer = new AuditConsumer(processedEventRepository, auditLogRepository, objectMapper);

        validPayload = """
                {"transactionId":"%s","type":"DEPOSIT","fromWalletId":null,"toWalletId":"%s",\
                "amountCents":5000,"currency":"EUR","completedAt":"2024-01-01T00:00:00Z"}"""
                .formatted(EVENT_ID, WALLET_ID);


    }

    @Test
    void shouldSaveAuditLogAndMarkEventProcessedWhenEventIsNew() {
        // Given
        when(processedEventRepository.existsByIdEventIdAndIdConsumerGroup(EVENT_ID, AUDIT_GROUP)).thenReturn(false);

        // When
        consumer.handle(validPayload,null);

        // Then
        ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(auditCaptor.capture());
        AuditLog saved = auditCaptor.getValue();
        assertThat(saved.getAction()).isEqualTo("DEPOSIT");
        assertThat(saved.getEntityType()).isEqualTo("Transaction");
        assertThat(saved.getEntityId()).isEqualTo(EVENT_ID);
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void shouldSkipAuditLogWhenSameEventAlreadyProcessedByAuditGroup() {
        // Given
        when(processedEventRepository.existsByIdEventIdAndIdConsumerGroup(EVENT_ID, AUDIT_GROUP)).thenReturn(true);

        // When
        consumer.handle(validPayload,null);

        // Then
        verify(auditLogRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    void shouldProcessEventWhenSameEventIdAlreadyProcessedByDifferentConsumerGroup() {
        // Given — analytics group processed this event but audit group has not
        when(processedEventRepository.existsByIdEventIdAndIdConsumerGroup(EVENT_ID, AUDIT_GROUP)).thenReturn(false);

        // When
        consumer.handle(validPayload,null);

        // Then — audit group is independent, writes its own processed_events row
        verify(auditLogRepository).save(any(AuditLog.class));
        verify(processedEventRepository).save(any(ProcessedEvent.class));
    }

    @Test
    void shouldThrowIllegalStateWhenPayloadIsInvalidJson() {
        // Given
        String invalidPayload = "not-valid-json";

        // When / Then
        assertThatThrownBy(() -> consumer.handle(invalidPayload, null))
                .isInstanceOf(IllegalStateException.class);
        verify(auditLogRepository, never()).save(any());
    }

    @Test
    void shouldNotSaveProcessedEventWhenRepositorySaveThrows() {
        // Given
        when(processedEventRepository.existsByIdEventIdAndIdConsumerGroup(EVENT_ID, AUDIT_GROUP)).thenReturn(false);
        when(auditLogRepository.save(any())).thenThrow(new RuntimeException());

        // When / Then
        assertThatThrownBy(() -> consumer.handle(validPayload,null))
                .isInstanceOf(RuntimeException.class);
        verify(processedEventRepository, never()).save(any());
    }
}
