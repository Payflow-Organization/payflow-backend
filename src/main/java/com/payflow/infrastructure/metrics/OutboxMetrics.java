package com.payflow.infrastructure.metrics;

import com.payflow.domain.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxMetrics {
    private final OutboxRepository outboxRepository;
    private final MeterRegistry meterRegistry;

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("payflow.outbox.pending.size", outboxRepository,
                        OutboxRepository::countPending)
                .description("Current count of PENDING outbox events")
                .register(meterRegistry);
    }
}
