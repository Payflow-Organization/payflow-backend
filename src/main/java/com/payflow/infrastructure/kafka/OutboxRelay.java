package com.payflow.infrastructure.kafka;

import com.payflow.domain.model.outbox.OutboxEvent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxService outboxService;
    private final TransactionEventPublisher publisher;
    private final MeterRegistry meterRegistry;

    @Value("${payflow.outbox.batch-size}")
    private Integer batchSize;

    @Scheduled(fixedDelayString = "${payflow.outbox.poll-interval-ms}")
    public void relay() {
        List<OutboxEvent> events = outboxService.fetchPending(batchSize);

        for (OutboxEvent event : events) {
            Timer.Sample timer = Timer.start(meterRegistry);
            try {
                publisher.publish(event);
                outboxService.markAsProcessed(event.getId());
                meterRegistry.counter("payflow.outbox.relay.processed").increment();
            } catch (Exception e) {
                meterRegistry.counter("payflow.outbox.relay.failure",
                        "reason", e.getClass().getSimpleName())
                        .increment();
                outboxService.incrementRetry(event.getId(), e.getMessage());
            }
            finally {
                timer.stop(Timer.builder("payflow.outbox.relay.latency").register(meterRegistry));
            }
        }
    }
}
