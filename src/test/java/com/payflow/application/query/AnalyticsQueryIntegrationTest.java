package com.payflow.application.query;

import com.payflow.BaseIntegrationTest;
import com.payflow.api.dto.response.BalanceHistoryResponse;
import com.payflow.api.dto.response.MonthlySummaryResponse;
import com.payflow.api.dto.response.SpendingByCategoryResponse;
import com.payflow.application.query.AnalyticsQueryHandler.*;
import com.payflow.application.service.WalletService;
import com.payflow.domain.model.ledger.LedgerEntry;
import com.payflow.domain.model.transaction.Transaction;
import com.payflow.domain.model.transaction.TransactionType;
import com.payflow.domain.model.user.User;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.repository.LedgerEntryRepository;
import com.payflow.infrastructure.persistence.jpa.TransactionJpaRepository;
import com.payflow.infrastructure.persistence.jpa.UserJpaRepository;
import com.payflow.infrastructure.persistence.jpa.WalletJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.payflow.domain.model.ledger.EntryType.CREDIT;
import static com.payflow.domain.model.ledger.EntryType.DEBIT;
import static org.assertj.core.api.Assertions.assertThat;

class AnalyticsQueryIntegrationTest extends BaseIntegrationTest {

    @Autowired private AnalyticsQueryHandler analyticsQueryHandler;
    @Autowired private LedgerEntryRepository ledgerEntryRepository;
    @Autowired private WalletJpaRepository walletRepository;
    @Autowired private WalletService walletService;
    @Autowired private UserJpaRepository userRepository;
    @Autowired private TransactionJpaRepository transactionRepository;

    private static final Currency GBP = Currency.getInstance("GBP");

    private User testUser;
    private Wallet testWallet;
    private Transaction depositTx;
    private Transaction withdrawTx;
    private Transaction transferTx;

    @BeforeEach
    void setUp() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();

        testUser = userRepository.save(User.builder()
                .fullName("Test User")
                .email("test@payflow.com")
                .passwordHash("hashedpassword")
                .build());

        testWallet = walletService.save(Wallet.create(testUser.getId(), GBP));

        depositTx = transactionRepository.save(Transaction.create(
                UUID.randomUUID().toString(), TransactionType.DEPOSIT,
                null, testWallet.getId(), 5000L, GBP, testUser.getId()
        ));

        withdrawTx = transactionRepository.save(Transaction.create(
                UUID.randomUUID().toString(), TransactionType.WITHDRAW,
                testWallet.getId(), null, 2000L, GBP, testUser.getId()
        ));

        transferTx = transactionRepository.save(Transaction.create(
                UUID.randomUUID().toString(), TransactionType.TRANSFER,
                testWallet.getId(), null, 1000L, GBP, testUser.getId()
        ));
    }

    // ------------------------------------------------------------------ balance history

    @Test
    void shouldReturnLastBalancePerBucketForRequestedPeriod() {
        // Given — three CREDIT entries on the same day, last one has balanceAfter=7000
        ledgerEntryRepository.save(entry(depositTx, CREDIT, 5000L, 5000L));
        ledgerEntryRepository.save(entry(depositTx, CREDIT, 1000L, 6000L));
        ledgerEntryRepository.save(entry(depositTx, CREDIT, 1000L, 7000L));

        var query = new BalanceHistoryQuery(
                testWallet.getId(), testUser.getId(),
                LocalDate.now(), LocalDate.now(),
                "1 day"
        );

        // When
        List<BalanceHistoryResponse> result = analyticsQueryHandler.balanceHistory(query);

        // Then — one bucket for today, last balance is 7000
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().lastBalanceCents()).isEqualTo(7000L);
    }

    @Test
    void shouldReturnEmptyBalanceHistoryWhenNoEntriesInPeriod() {
        // Given — no ledger entries

        var query = new BalanceHistoryQuery(
                testWallet.getId(), testUser.getId(),
                LocalDate.now().minusDays(7), LocalDate.now().minusDays(1),
                "1 day"
        );

        // When
        List<BalanceHistoryResponse> result = analyticsQueryHandler.balanceHistory(query);

        // Then
        assertThat(result).isEmpty();
    }


    @Test
    void shouldReturnSpendingTotalGroupedByTransactionType() {
        // Given — 2000 WITHDRAW debit, 1000 TRANSFER debit
        ledgerEntryRepository.save(entry(withdrawTx, DEBIT, 2000L, 3000L));
        ledgerEntryRepository.save(entry(transferTx, DEBIT, 1000L, 2000L));

        var query = new SpendingByCategoryQuery(
                testWallet.getId(), testUser.getId(),
                LocalDate.now(), LocalDate.now()
        );

        // When
        List<SpendingByCategoryResponse> result = analyticsQueryHandler.spendingByCategory(query);

        // Then
        assertThat(result).hasSize(2);

        var withdraw = result.stream().filter(r -> Objects.equals(r.transactionType(), TransactionType.WITHDRAW)).findFirst().orElseThrow();
        assertThat(withdraw.totalCents()).isEqualTo(2000L);
        assertThat(withdraw.count()).isEqualTo(1L);

        var transfer = result.stream().filter(r -> Objects.equals(r.transactionType(), TransactionType.TRANSFER)).findFirst().orElseThrow();
        assertThat(transfer.totalCents()).isEqualTo(1000L);
        assertThat(transfer.count()).isEqualTo(1L);
    }

    @Test
    void shouldExcludeCreditEntriesFromSpendingBreakdown() {
        // Given — one CREDIT deposit, one DEBIT withdraw
        ledgerEntryRepository.save(entry(depositTx, CREDIT, 5000L, 5000L));
        ledgerEntryRepository.save(entry(withdrawTx, DEBIT, 2000L, 3000L));

        var query = new SpendingByCategoryQuery(
                testWallet.getId(), testUser.getId(),
                LocalDate.now(), LocalDate.now()
        );

        // When
        List<SpendingByCategoryResponse> result = analyticsQueryHandler.spendingByCategory(query);

        // Then — only DEBIT entries appear, DEPOSIT credit is excluded
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().transactionType()).isEqualTo(TransactionType.WITHDRAW);
    }

    // ------------------------------------------------------------------ monthly summary

    @Test
    void shouldReturnCorrectDepositWithdrawalAndNetForMonth() {
        // Given — 5000 deposit credit, 2000 withdraw debit
        ledgerEntryRepository.save(entry(depositTx, CREDIT, 5000L, 5000L));
        ledgerEntryRepository.save(entry(withdrawTx, DEBIT, 2000L, 3000L));

        var query = new MonthlySummaryQuery(
                testWallet.getId(), testUser.getId(),
                YearMonth.now()
        );

        // When
        MonthlySummaryResponse result = analyticsQueryHandler.monthlySummary(query);

        // Then
        assertThat(result.totalDepositsCents()).isEqualTo(5000L);
        assertThat(result.totalWithdrawalsCents()).isEqualTo(2000L);
        assertThat(result.netCents()).isEqualTo(3000L);
        assertThat(result.transactionCount()).isEqualTo(2L);
    }

    @Test
    void shouldReturnZeroTotalsWhenNoEntriesInMonth() {
        // Given — no ledger entries

        var query = new MonthlySummaryQuery(
                testWallet.getId(), testUser.getId(),
                YearMonth.now().minusMonths(1)
        );

        // When
        MonthlySummaryResponse result = analyticsQueryHandler.monthlySummary(query);

        // Then — COALESCE guarantees zeros, not nulls
        assertThat(result.totalDepositsCents()).isZero();
        assertThat(result.totalWithdrawalsCents()).isZero();
        assertThat(result.netCents()).isZero();
        assertThat(result.transactionCount()).isZero();
    }


    private LedgerEntry entry(Transaction tx, com.payflow.domain.model.ledger.EntryType type, long amount, long balanceAfter) {
        return LedgerEntry.builder()
                .transactionId(tx.getId())
                .walletId(testWallet.getId())
                .amount(amount)
                .entryType(type)
                .balanceAfter(balanceAfter)
                .build();
    }
}