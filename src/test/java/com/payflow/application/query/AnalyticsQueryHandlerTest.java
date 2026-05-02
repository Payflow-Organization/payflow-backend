package com.payflow.application.query;

import com.payflow.api.dto.response.BalanceHistoryResponse;
import com.payflow.api.dto.response.MonthlySummaryResponse;
import com.payflow.api.dto.response.SpendingByCategoryResponse;
import com.payflow.application.query.AnalyticsQueryHandler.*;
import com.payflow.domain.model.transaction.TransactionType;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.model.wallet.WalletNotFoundException;
import com.payflow.domain.repository.LedgerEntryRepository;
import com.payflow.domain.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AnalyticsQueryHandlerTest {

    @Mock
    private LedgerEntryRepository ledgerEntryRepository;

    @Mock
    private WalletRepository walletRepository;

    @InjectMocks
    private AnalyticsQueryHandler handler;

    private static final UUID USER_ID   = UUID.randomUUID();
    private static final UUID WALLET_ID = UUID.randomUUID();
    private static final UUID OTHER_ID  = UUID.randomUUID();
    private static final Currency EUR   = Currency.getInstance("EUR");

    private Wallet walletOwnedByUser()  { return Wallet.create(USER_ID, EUR); }
    private Wallet walletOwnedByOther() { return Wallet.create(OTHER_ID, EUR); }

    // ------------------------------------------------------------------ balance history

    @Test
    void shouldReturnBalanceHistoryForAuthenticatedOwner() {
        // Given
        var query    = new BalanceHistoryQuery(WALLET_ID, USER_ID, LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30), "1 day");
        var expected = List.of(new BalanceHistoryResponse(Instant.now(), 5000L));

        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(walletOwnedByUser()));
        when(ledgerEntryRepository.findBalanceHistory(eq(WALLET_ID), any(), any(), eq("1 day"))).thenReturn(expected);

        // When
        var result = handler.balanceHistory(query);

        // Then
        assertThat(result).isSameAs(expected);
    }

    @Test
    void shouldDenyBalanceHistoryWhenWalletDoesNotExist() {
        // Given
        var query = new BalanceHistoryQuery(WALLET_ID, USER_ID, LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30), "1 day");

        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> handler.balanceHistory(query))
                .isInstanceOf(WalletNotFoundException.class);

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void shouldDenyBalanceHistoryWhenWalletBelongsToAnotherUser() {
        // Given
        var query = new BalanceHistoryQuery(WALLET_ID, USER_ID, LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30), "1 day");

        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(walletOwnedByOther()));

        // When / Then
        assertThatThrownBy(() -> handler.balanceHistory(query))
                .isInstanceOf(WalletNotFoundException.class);

        verifyNoInteractions(ledgerEntryRepository);
    }

    // ------------------------------------------------------------------ spending by category

    @Test
    void shouldReturnSpendingBreakdownForAuthenticatedOwner() {
        // Given
        var query    = new SpendingByCategoryQuery(WALLET_ID, USER_ID, LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));
        var expected = List.of(new SpendingByCategoryResponse(TransactionType.WITHDRAW, 3000L, 2L));

        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(walletOwnedByUser()));
        when(ledgerEntryRepository.findSpendingByCategory(eq(WALLET_ID), any(), any())).thenReturn(expected);

        // When
        var result = handler.spendingByCategory(query);

        // Then
        assertThat(result).isSameAs(expected);
    }

    @Test
    void shouldDenySpendingBreakdownWhenWalletDoesNotExist() {
        // Given
        var query = new SpendingByCategoryQuery(WALLET_ID, USER_ID, LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));

        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> handler.spendingByCategory(query))
                .isInstanceOf(WalletNotFoundException.class);

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void shouldDenySpendingBreakdownWhenWalletBelongsToAnotherUser() {
        // Given
        var query = new SpendingByCategoryQuery(WALLET_ID, USER_ID, LocalDate.of(2025, 4, 1), LocalDate.of(2025, 4, 30));

        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(walletOwnedByOther()));

        // When / Then
        assertThatThrownBy(() -> handler.spendingByCategory(query))
                .isInstanceOf(WalletNotFoundException.class);

        verifyNoInteractions(ledgerEntryRepository);
    }


    @Test
    void shouldReturnMonthlySummaryForAuthenticatedOwner() {
        // Given
        var query    = new MonthlySummaryQuery(WALLET_ID, USER_ID, YearMonth.of(2025, 4));
        var expected = new MonthlySummaryResponse(10000L, 3000L, 7000L, 5L);

        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(walletOwnedByUser()));
        when(ledgerEntryRepository.findMonthlySummary(eq(WALLET_ID), any(), any())).thenReturn(expected);

        // When
        var result = handler.monthlySummary(query);

        // Then
        assertThat(result).isSameAs(expected);
    }

    @Test
    void shouldDenyMonthlySummaryWhenWalletDoesNotExist() {
        // Given
        var query = new MonthlySummaryQuery(WALLET_ID, USER_ID, YearMonth.of(2025, 4));

        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.empty());

        // When / Then
        assertThatThrownBy(() -> handler.monthlySummary(query))
                .isInstanceOf(WalletNotFoundException.class);

        verifyNoInteractions(ledgerEntryRepository);
    }

    @Test
    void shouldDenyMonthlySummaryWhenWalletBelongsToAnotherUser() {
        // Given
        var query = new MonthlySummaryQuery(WALLET_ID, USER_ID, YearMonth.of(2025, 4));

        when(walletRepository.findById(WALLET_ID)).thenReturn(Optional.of(walletOwnedByOther()));

        // When / Then
        assertThatThrownBy(() -> handler.monthlySummary(query))
                .isInstanceOf(WalletNotFoundException.class);

        verifyNoInteractions(ledgerEntryRepository);
    }
}