package com.payflow.application.command.wallet;

import com.payflow.api.dto.response.WalletResponse;
import com.payflow.application.service.WalletService;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.model.wallet.WalletAlreadyExistsException;
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
class CreateWalletCommandHandlerTest {

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private WalletService walletService;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private CreateWalletCommandHandler handler;

    private static final Currency EUR = Currency.getInstance("EUR");

    @BeforeEach
    void setUp() {
        handler = new CreateWalletCommandHandler(
                walletService,
                walletRepository,
                meterRegistry
        );
    }

    @Test
    void shouldCreateWalletForNewCurrency() {
        UUID userId = UUID.randomUUID();
        when(walletRepository.findByUserIdAndCurrency(userId, EUR)).thenReturn(Optional.empty());

        WalletResponse response = handler.handle(new CreateWalletCommandHandler.Command(userId, EUR));

        assertThat(response.currency()).isEqualTo(EUR);
        assertThat(response.balance()).isZero();
        verify(walletService).save(any(Wallet.class));
    }

    @Test
    void shouldThrowWhenWalletForCurrencyAlreadyExists() {
        UUID userId = UUID.randomUUID();
        Wallet existing = Wallet.create(userId, EUR);
        when(walletRepository.findByUserIdAndCurrency(userId, EUR)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> handler.handle(new CreateWalletCommandHandler.Command(userId, EUR)))
                .isInstanceOf(WalletAlreadyExistsException.class);

        verify(walletService, never()).save(any());
    }


}
