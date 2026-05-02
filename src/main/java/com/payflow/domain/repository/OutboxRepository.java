package com.payflow.domain.repository;

import com.payflow.domain.model.outbox.OutboxEvent;
import com.payflow.domain.model.outbox.OutboxEventStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxRepository {
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxEventStatus status, int limit);
    Optional<OutboxEvent> findById(UUID id);
    OutboxEvent save(OutboxEvent outboxEvent);
    long countPending();
}