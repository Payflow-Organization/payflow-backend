package com.payflow.application.command.transactions;

import com.payflow.application.service.IdempotencyService;
import com.payflow.application.service.LedgerService;
import com.payflow.application.service.WalletService;
import com.payflow.domain.model.transaction.Transaction;
import com.payflow.domain.model.transaction.TransactionStatus;
import com.payflow.domain.model.transaction.TransactionType;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.repository.TransactionRepository;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DepositCommandHandlerTest {
    @Mock
    private WalletService walletService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private TransactionOutboxWriter eventPublisher;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private DepositCommandHandler handler;

    private static final UUID USER_ID   = UUID.randomUUID();
    private static final UUID WALLET_ID = UUID.randomUUID();
    private static final Currency EUR   = Currency.getInstance("EUR");

    private DepositCommandHandler.Command command(long amountCents) {
        return new DepositCommandHandler.Command("idem-key-1", WALLET_ID, USER_ID, amountCents);
    }

    @BeforeEach
    void setUp() {
        handler = new DepositCommandHandler(
                walletService,
                idempotencyService,
                transactionRepository,
                ledgerService,
                eventPublisher,
                meterRegistry
        );
    }

    private Wallet activeWallet(long amountCents) {
       Wallet wallet = Wallet.create(USER_ID,EUR);
       wallet.credit(amountCents);
       return wallet;
    }

    @Test
    void shouldDepositCreditWalletAndReturnCompletedTransaction() {
        // Given
        Wallet wallet = activeWallet(10_000L);

        when(idempotencyService.findDuplicate("idem-key-1"))
                .thenReturn(Optional.empty());
        when(walletService.getActiveById(WALLET_ID, USER_ID))
                .thenReturn(wallet);
        when(idempotencyService.deduplicateOrSave(any()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        // When
        Transaction result = handler.handle(command(3000L));

        // Then
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.getType()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(result.getToWalletId()).isEqualTo(WALLET_ID);
        assertThat(result.getFromWalletId()).isNull();
        assertThat(result.getAmount()).isEqualTo(3000L);
        assertThat(wallet.getCurrentBalance()).isEqualTo(13_000L);
        verify(ledgerService)
                .createCreditEntry(any(Transaction.class), eq(wallet),
                        eq(3000L));
        verify(eventPublisher)
                .publishTransactionCreated(any(Transaction.class),eq(USER_ID));
    }

    @Test
    void shouldThrowWhenAmountIsZeroOrNegative() {
        assertThatThrownBy(() -> command(0L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> command(-1L))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(walletService, idempotencyService, ledgerService, eventPublisher, transactionRepository);
    }

    @Test
    void shouldReturnExistingTransactionOnDuplicateKey() {
        // Given
        Transaction existing = Transaction.create("idem-key-1", TransactionType.DEPOSIT,
                null, WALLET_ID, 5000L, EUR, USER_ID);
        existing.complete();

        when(idempotencyService.findDuplicate("idem-key-1")).thenReturn(Optional.of(existing));

        // When
        Transaction result = handler.handle(command(5000L));

        // Then
        assertThat(result).isSameAs(existing);
        verifyNoInteractions(walletService, ledgerService, eventPublisher, transactionRepository);
    }
}