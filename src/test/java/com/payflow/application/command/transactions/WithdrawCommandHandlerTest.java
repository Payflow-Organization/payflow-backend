package com.payflow.application.command.transactions;

import com.payflow.application.service.IdempotencyService;
import com.payflow.application.service.LedgerService;
import com.payflow.application.service.WalletService;
import com.payflow.domain.model.transaction.Transaction;
import com.payflow.domain.model.transaction.TransactionStatus;
import com.payflow.domain.model.transaction.TransactionType;
import com.payflow.domain.model.wallet.InsufficientBalanceException;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.repository.TransactionRepository;
import com.payflow.domain.repository.WalletRepository;
import com.payflow.infrastructure.kafka.TransactionOutboxWriter;
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
class WithdrawCommandHandlerTest {

    @Mock
    private WalletService walletService;

    @Mock
    private WalletRepository walletRepository;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private LedgerService ledgerService;


    @Mock
    private TransactionOutboxWriter eventPublisher;

    private WithdrawCommandHandler handler;

    private static final UUID USER_ID   = UUID.randomUUID();
    private static final UUID WALLET_ID = UUID.randomUUID();
    private static final Currency EUR   = Currency.getInstance("EUR");
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private WithdrawCommandHandler.Command command(long amountCents) {
        return new WithdrawCommandHandler.Command("idem-key-1", WALLET_ID, USER_ID, amountCents);
    }
    @BeforeEach
    void setUp() {
        handler = new WithdrawCommandHandler(
                walletService,
                idempotencyService,
                transactionRepository,
                ledgerService,
                eventPublisher,
                meterRegistry
        );
    }

    private Wallet activeWallet(long balance) {
        Wallet wallet = Wallet.create(USER_ID, EUR);
        wallet.credit(balance);
        return wallet;
    }



    @Test
    void shouldWithdrawDebitWalletAndReturnCompletedTransaction() {
        // Given
        Wallet wallet = activeWallet(10_000L);

        when(idempotencyService.findDuplicate("idem-key-1")).thenReturn(Optional.empty());
        when(walletService.getActiveById(WALLET_ID, USER_ID)).thenReturn(wallet);
        when(idempotencyService.deduplicateOrSave(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        Transaction result = handler.handle(command(3000L));

        // Then
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.getType()).isEqualTo(TransactionType.WITHDRAW);
        assertThat(result.getFromWalletId()).isEqualTo(WALLET_ID);
        assertThat(result.getToWalletId()).isNull();
        assertThat(result.getAmount()).isEqualTo(3000L);
        assertThat(wallet.getCurrentBalance()).isEqualTo(7_000L);
        verify(ledgerService).createDebitEntry(any(Transaction.class), eq(wallet), eq(3000L));
        verify(eventPublisher).publishTransactionCreated(any(Transaction.class),eq(USER_ID));
    }


    @Test
    void shouldReturnExistingTransactionOnDuplicateKey() {
        // Given
        Transaction existing = Transaction.create("idem-key-1", TransactionType.WITHDRAW,
                WALLET_ID, null, 3000L, EUR, USER_ID);
        existing.complete();

        when(idempotencyService.findDuplicate("idem-key-1")).thenReturn(Optional.of(existing));

        // When
        Transaction result = handler.handle(command(3000L));

        // Then
        assertThat(result).isSameAs(existing);
        verifyNoInteractions(walletService, walletRepository, ledgerService, eventPublisher, transactionRepository);
    }

    @Test
    void shouldThrowWhenInsufficientBalanceWhenBalanceBelowAmount() {
        // Given
        Wallet wallet = activeWallet(1000L);

        when(idempotencyService.findDuplicate(any())).thenReturn(Optional.empty());
        when(walletService.getActiveById(any(), any())).thenReturn(wallet);

        // When / Then
        var command = command(1001L);
        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(InsufficientBalanceException.class);

        verifyNoInteractions(ledgerService, eventPublisher, transactionRepository);
    }

}
