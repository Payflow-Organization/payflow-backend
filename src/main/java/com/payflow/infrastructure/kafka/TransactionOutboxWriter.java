package com.payflow.infrastructure.kafka;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import com.payflow.domain.model.outbox.OutboxEvent;
import com.payflow.domain.model.outbox.OutboxEventStatus;
import com.payflow.domain.model.transaction.Transaction;
import com.payflow.domain.model.transaction.TransactionType;
import com.payflow.domain.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class TransactionOutboxWriter {
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public void publishTransactionCreated(Transaction tx, UUID userId)
    {
        OutboxEvent event =  OutboxEvent.builder()
                .aggregateId(tx.getId())
                .aggregateType(tx.getClass().getSimpleName())
                .eventType("TransactionCreated")
                .payload(serialize(toPayload(tx, userId)))
                .status(OutboxEventStatus.PENDING)
                .build();
        outboxRepository.save(event); // ADR-013: outbox written in same transaction as state change — Kafka publish happens separately via OutboxRelay
    }

    private TransactionCreatedPayload toPayload(Transaction tx, UUID userId)
    {
        return new TransactionCreatedPayload(
                tx.getId(),
                userId,
                tx.getType(),
                tx.getFromWalletId(),
                tx.getToWalletId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getCompletedAt()
        );
    }

    private String serialize(TransactionCreatedPayload payload)
    {
        try {
            return objectMapper.writeValueAsString(payload);
        }
        catch (JacksonException e)
        {
            throw new IllegalStateException("Failed to serialize outbox payload: " + payload.getClass().getSimpleName(), e);
        }
    }

    public record TransactionCreatedPayload(
            UUID transactionId,
            UUID userId,
            TransactionType type,
            UUID fromWalletId,
            UUID toWalletId,
            long amountCents,
            Currency currency,
            Instant completedAt
    ) {}
}
