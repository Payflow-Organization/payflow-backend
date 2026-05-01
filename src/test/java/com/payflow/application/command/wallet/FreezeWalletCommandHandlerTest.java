package com.payflow.application.command.wallet;

import com.payflow.application.service.WalletService;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.model.wallet.WalletNotFoundException;
import com.payflow.domain.model.wallet.WalletStatus;
import com.payflow.domain.repository.WalletRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FreezeWalletCommandHandlerTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private WalletService walletService;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private FreezeWalletCommandHandler handler;

    @BeforeEach
    void setUp() {
        handler = new FreezeWalletCommandHandler(
                walletRepository,
                walletService,
                meterRegistry);
    }

    @Test
    void shouldFreezeActiveWallet() {
        UUID userId = UUID.randomUUID();
        Wallet wallet = Wallet.create(userId, Currency.getInstance("GBP"));
        when(walletRepository.findByIdAndUserId(wallet.getId(), userId)).thenReturn(Optional.of(wallet));

        handler.handle(new FreezeWalletCommandHandler.Command(wallet.getId(), userId));

        assertThat(wallet.getStatus()).isEqualTo(WalletStatus.FROZEN);
        verify(walletService).save(wallet);
    }

    @Test
    void shouldThrowWhenWalletNotFound() {
        UUID walletId = UUID.randomUUID();
        when(walletRepository.findByIdAndUserId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new FreezeWalletCommandHandler.Command(walletId, UUID.randomUUID())))
                .isInstanceOf(WalletNotFoundException.class);

        verify(walletService, never()).save(any());
    }

    @Test
    void shouldThrowWhenUserDoesNotOwnWallet() {
        UUID ownerId = UUID.randomUUID();
        UUID otherUserId = UUID.randomUUID();
        Wallet wallet = Wallet.create(ownerId, Currency.getInstance("GBP"));
        when(walletRepository.findByIdAndUserId(wallet.getId(), otherUserId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.handle(new FreezeWalletCommandHandler.Command(wallet.getId(), otherUserId)))
                .isInstanceOf(WalletNotFoundException.class);

        verify(walletService, never()).save(any());
    }

    @Test
    void shouldThrowWhenWalletIsAlreadyFrozen() {
        UUID userId = UUID.randomUUID();
        Wallet wallet = Wallet.create(userId, Currency.getInstance("GBP"));
        wallet.freeze();
        when(walletRepository.findByIdAndUserId(wallet.getId(), userId)).thenReturn(Optional.of(wallet));

        assertThatThrownBy(() -> handler.handle(new FreezeWalletCommandHandler.Command(wallet.getId(), userId)))
                .isInstanceOf(IllegalStateException.class);
    }
}
