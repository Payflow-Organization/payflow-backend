package com.payflow.domain.model.wallet;

import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.assertThatThrownBy;


class WalletTest {

    @Test
    void shouldCreateWalletWithZeroBalance() {
        //When
        Wallet wallet = Wallet.create(UUID.randomUUID(), Currency.getInstance("GBP"));

        //Then
        assertThat(wallet.getCurrentBalance()).isZero();
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(wallet.getCurrency()).isEqualTo(Currency.getInstance("GBP"));
    }

    @Test
    void shouldReduceBalanceOnDebit() {
        // Given
        Wallet wallet = Wallet.create(UUID.randomUUID(), Currency.getInstance("GBP"));
        wallet.credit(1000L);

        // When
        wallet.debit(400L);

        // Then
        assertThat(wallet.getCurrentBalance()).isEqualTo(600L);
    }

    @Test
    void shouldThrowWhenValidateSufficientBalanceExceedsBalance() {
        // Given
        Wallet wallet = Wallet.create(UUID.randomUUID(), Currency.getInstance("GBP"));
        wallet.credit(100L);

        // When + Then
        assertThatThrownBy(() -> wallet.validateSufficientBalance(200L))
                .isInstanceOf(InsufficientBalanceException.class);
    }

    @Test
    void shouldIncreaseBalanceOnCredit() {
        // Given
        Wallet wallet = Wallet.create(UUID.randomUUID(), Currency.getInstance("GBP"));

        // When
        wallet.credit(500L);

        // Then
        assertThat(wallet.getCurrentBalance()).isEqualTo(500L);
    }

    @Test
    void shouldFreezeActiveWallet() {
        // Given
        Wallet wallet = Wallet.create(UUID.randomUUID(), Currency.getInstance("GBP"));

        // When
        wallet.freeze();

        // Then
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.FROZEN);
    }

    @Test
    void shouldThrowWhenFreezingNonActiveWallet() {
        // Given
        Wallet wallet = Wallet.create(UUID.randomUUID(), Currency.getInstance("GBP"));
        wallet.freeze();

        // When / Then
        assertThatThrownBy(wallet::freeze)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void shouldUnfreezeFrozenWallet() {
        // Given
        Wallet wallet = Wallet.create(UUID.randomUUID(), Currency.getInstance("GBP"));
        wallet.freeze();

        // When
        wallet.unfreeze();

        // Then
        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.ACTIVE);
    }

    @Test
    void shouldThrowWhenUnfreezingActiveWallet() {
        // Given
        Wallet wallet = Wallet.create(UUID.randomUUID(), Currency.getInstance("GBP"));

        // When / Then
        assertThatThrownBy(wallet::unfreeze)
                .isInstanceOf(IllegalStateException.class);
    }
}