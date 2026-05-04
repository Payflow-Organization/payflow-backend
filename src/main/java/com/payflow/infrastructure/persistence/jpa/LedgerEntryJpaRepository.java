package com.payflow.infrastructure.persistence.jpa;

import com.payflow.api.dto.response.BalanceHistoryResponse;
import com.payflow.api.dto.response.MonthlySummaryResponse;
import com.payflow.api.dto.response.SpendingByCategoryResponse;
import com.payflow.application.dto.TransactionView;
import com.payflow.domain.model.ledger.LedgerEntry;
import com.payflow.domain.repository.LedgerEntryRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public interface LedgerEntryJpaRepository extends JpaRepository<LedgerEntry, UUID>, LedgerEntryRepository {

    @Query(value = """
    SELECT
        time_bucket(CAST(:interval AS interval), created_at) AS bucket,
        LAST(balance_after, created_at)                      AS lastBalanceCents
    FROM ledger_entries
    WHERE wallet_id   = :walletId
      AND created_at >= :from
      AND created_at  < :to
    GROUP BY 1
    ORDER BY 1
    """, nativeQuery = true)
    List<BalanceHistoryResponse> findBalanceHistory(
            @Param("walletId") UUID walletId,
            @Param("from")     Instant from,
            @Param("to")       Instant to,
            @Param("interval") String interval
    );

    @Query(value = """
        SELECT
            t.type                                        AS transactionType,
            SUM(le.amount)                     AS totalCents,
            COUNT(DISTINCT le.transactionId)     AS count
        FROM LedgerEntry le
        JOIN Transaction t ON t.id = le.transactionId
        WHERE le.walletId   = :walletId
          AND le.createdAt >= :from
          AND le.createdAt  < :to
          AND le.entryType  = 'DEBIT'
        GROUP BY t.type
        ORDER BY SUM(le.amount) DESC
        """)
    List<SpendingByCategoryResponse> findSpendingByCategory(
            @Param("walletId") UUID walletId,
            @Param("from")     Instant from,
            @Param("to")       Instant to
    );

    @Query("""
    SELECT new com.payflow.api.dto.response.MonthlySummaryResponse(
        COALESCE(SUM(le.amount) FILTER (WHERE le.entryType = 'CREDIT'), 0),
        COALESCE(SUM(le.amount) FILTER (WHERE le.entryType = 'DEBIT'),  0),
        COALESCE(SUM(le.amount) FILTER (WHERE le.entryType = 'CREDIT'), 0) -
        COALESCE(SUM(le.amount) FILTER (WHERE le.entryType = 'DEBIT'),  0),
        COUNT(DISTINCT le.transactionId)
    )
    FROM LedgerEntry le
    WHERE le.walletId = :walletId
      AND le.createdAt >= :from
      AND le.createdAt < :to
    """)
    MonthlySummaryResponse findMonthlySummary(
            @Param("walletId") UUID walletId,
            @Param("from")     Instant from,
            @Param("to")       Instant to
    );

    @Query("""
        SELECT new com.payflow.application.dto.TransactionView(
            le.transactionId,
            le.createdAt,
            le.entryType,
            le.amount,
            le.balanceAfter
        )
        FROM LedgerEntry le
        WHERE le.walletId = :walletId
          AND le.createdAt BETWEEN :from AND :to
        ORDER BY le.createdAt
        """)
    Stream<TransactionView> findStatementRows(
            @Param("walletId") UUID walletId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );
    @Query(value = """
    SELECT
        COALESCE(SUM(amount) FILTER (WHERE entryType = 'CREDIT'), 0)  -
        COALESCE(SUM(amount) FILTER (WHERE entryType = 'DEBIT'),  0)
    FROM LedgerEntry
    """)
    long findGlobalImbalance();
}