package com.payflow.domain.repository;

import com.payflow.domain.model.transaction.Transaction;
import com.payflow.domain.model.transaction.TransactionStatus;
import com.payflow.domain.model.transaction.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public interface TransactionRepository {
    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);
    Page<Transaction> findFiltered(UUID walletId,
                                   TransactionType type,
                                   TransactionStatus status,
                                   Pageable pageable);
    Optional<Transaction> findByIdAndUserId(UUID id, UUID userId);
    Transaction save(Transaction transaction);
    Stream<Transaction> findByWalletIdBetween(UUID walletId, Instant from, Instant to);
}