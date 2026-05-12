package com.payflow.infrastructure.persistence.jpa;

import com.payflow.domain.model.transaction.Transaction;
import com.payflow.domain.model.transaction.TransactionStatus;
import com.payflow.domain.model.transaction.TransactionType;
import com.payflow.domain.repository.TransactionRepository;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.hibernate.jpa.HibernateHints.HINT_FETCH_SIZE;

public interface TransactionJpaRepository extends JpaRepository<Transaction,UUID >, TransactionRepository {
    @Override
    Transaction save(Transaction transaction);

    @Query("SELECT t FROM Transaction t WHERE (t.fromWalletId = :walletId OR t.toWalletId = :walletId) AND t.createdAt BETWEEN :from AND :to")
    @QueryHints(@QueryHint(name = HINT_FETCH_SIZE, value = "1000"))
    @Override
    Stream<Transaction> findByWalletIdBetween(
            @Param("walletId") UUID walletId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Query("""
      SELECT t FROM Transaction t
      WHERE (:walletId IS NULL OR t.fromWalletId = :walletId OR t.toWalletId
   = :walletId)
      AND (:type   IS NULL OR t.type   = :type)
      AND (:status IS NULL OR t.status = :status)
      ORDER BY t.createdAt DESC
     \s""")
    Page<Transaction> findFiltered(
            @Param("walletId") UUID walletId,
            @Param("type")     TransactionType type,
            @Param("status")   TransactionStatus status,
            Pageable pageable);
}