package com.payflow.infrastructure.kafka;

import com.payflow.domain.model.outbox.OutboxEvent;
import com.payflow.domain.model.outbox.OutboxEventStatus;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock private OutboxService outboxService;
    @Mock private TransactionEventPublisher publisher;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(
                outboxService,
                publisher,
                meterRegistry);
    }

    private OutboxEvent pendingEvent(int retryCount) {
        OutboxEvent event = new OutboxEvent();
        ReflectionTestUtils.setField(event, "id", UUID.randomUUID());
        event.setStatus(OutboxEventStatus.PENDING);
        event.setRetryCount(retryCount);
        return event;
    }


    @Test
    void firstFailureStaysPending() {
        // Given
        ReflectionTestUtils.setField(relay, "batchSize", 10);
        OutboxEvent event = pendingEvent(0);
        when(outboxService.fetchPending(10)).thenReturn(List.of(event));
        doThrow(new RuntimeException("broker down")).when(publisher).publish(event);

        // When
        relay.relay();

        // Then
        verify(outboxService).incrementRetry(event.getId(), "broker down");
        verify(outboxService, never()).markAsProcessed(event.getId());
    }

    @Test
    void nthFailureOnlyCallsIncrementRetry() {
        // Given
        ReflectionTestUtils.setField(relay, "batchSize", 10);
        OutboxEvent event = pendingEvent(2);
        when(outboxService.fetchPending(10)).thenReturn(List.of(event));
        doThrow(new RuntimeException("broker down")).when(publisher).publish(event);

        // When
        relay.relay();

        // Then
        verify(outboxService).incrementRetry(event.getId(), "broker down");
        verify(outboxService, never()).markAsProcessed(event.getId());
    }

    @Test
    void successfulPublishMarksProcessed() {
        // Given
        ReflectionTestUtils.setField(relay, "batchSize", 10);
        OutboxEvent event = pendingEvent(0);
        when(outboxService.fetchPending(10)).thenReturn(List.of(event));

        // When
        relay.relay();

        // Then
        verify(outboxService).markAsProcessed(event.getId());
        verify(outboxService, never()).incrementRetry(any(), any());
    }
}