package com.payflow.infrastructure.persistence.jpa;

import com.payflow.domain.model.outbox.OutboxEvent;
import com.payflow.domain.repository.OutboxRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

public interface OutboxJpaRepository extends JpaRepository<OutboxEvent, UUID>, OutboxRepository {
    @Override
    @Query("SELECT COUNT(e) FROM OutboxEvent e WHERE e.status = 'PENDING'")
    long countPending();
}