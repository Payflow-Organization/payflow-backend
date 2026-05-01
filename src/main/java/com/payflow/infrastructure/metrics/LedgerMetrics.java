package com.payflow.infrastructure.metrics;

import com.payflow.infrastructure.persistence.jpa.LedgerEntryJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class LedgerMetrics {
    private final LedgerEntryJpaRepository ledgerEntryRepository;
    private final MeterRegistry meterRegistry;
    @PostConstruct
    public void registerGauges() {
        Gauge.builder("payflow.ledger.imbalance", ledgerEntryRepository, repo ->
                repo.findGlobalImbalance().doubleValue())
                .description("SUM(CREDIT - DEBIT), should always be 0")
                .register(meterRegistry);
    }
}
