package com.payflow.application.command.transactions;

import com.payflow.application.service.IdempotencyService;
import com.payflow.application.service.LedgerService;
import com.payflow.application.service.WalletService;
import com.payflow.domain.model.transaction.CurrencyMismatchException;
import com.payflow.domain.model.transaction.InvalidWalletOperationException;
import com.payflow.domain.model.transaction.Transaction;
import com.payflow.domain.model.transaction.TransactionStatus;
import com.payflow.domain.model.transaction.TransactionType;
import com.payflow.domain.model.wallet.InsufficientBalanceException;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.model.wallet.WalletNotFoundException;
import com.payflow.domain.model.wallet.WalletStatus;
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
class TransferCommandHandlerTest {

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

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private TransferCommandHandler handler;


    private static final UUID USER_ID   = UUID.randomUUID();
    private static final UUID SOURCE_ID = UUID.randomUUID();
    private static final UUID DEST_ID   = UUID.randomUUID();
    private static final Currency EUR   = Currency.getInstance("EUR");
    private static final Currency GBP   = Currency.getInstance("GBP");

    private TransferCommandHandler.Command command(UUID destId, long amountCents) {
        return new TransferCommandHandler.Command("idem-key-1", TransferCommandHandlerTest.SOURCE_ID, destId, USER_ID, amountCents);
    }

    private Wallet wallet(UUID userId, Currency currency, long balance) {
        Wallet wallet = Wallet.create(userId, currency);
        wallet.credit(balance);
        return wallet;
    }


    @Test
    void shouldTransferDebitSourceCreditDestinationAndReturnCompletedTransaction() {
        // Given
        Wallet source = wallet(USER_ID, EUR, 10_000L);
        Wallet dest   = wallet(UUID.randomUUID(), EUR, 5_000L);

        when(idempotencyService.findDuplicate("idem-key-1")).thenReturn(Optional.empty());
        when(walletService.getActiveById(SOURCE_ID, USER_ID)).thenReturn(source);
        when(walletRepository.findByIdAndStatus(DEST_ID, WalletStatus.ACTIVE)).thenReturn(Optional.of(dest));
        when(idempotencyService.deduplicateOrSave(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // When
        Transaction result = handler.handle(command(DEST_ID, 3000L));

        // Then
        assertThat(result.getStatus()).isEqualTo(TransactionStatus.SUCCESS);
        assertThat(result.getType()).isEqualTo(TransactionType.TRANSFER);
        assertThat(result.getFromWalletId()).isEqualTo(SOURCE_ID);
        assertThat(result.getToWalletId()).isEqualTo(DEST_ID);
        assertThat(result.getAmount()).isEqualTo(3000L);
        assertThat(source.getCurrentBalance()).isEqualTo(7_000L);
        assertThat(dest.getCurrentBalance()).isEqualTo(8_000L);
        verify(ledgerService).createDebitEntry(any(Transaction.class), eq(source), eq(3000L));
        verify(ledgerService).createCreditEntry(any(Transaction.class), eq(dest), eq(3000L));
        verify(eventPublisher).publishTransactionCreated(any(Transaction.class),eq(USER_ID));
    }



    @Test
    void shouldReturnExistingTransactionOnDuplicateKey() {
        // Given
        Transaction existing = Transaction.create("idem-key-1", TransactionType.TRANSFER,
                SOURCE_ID, DEST_ID, 3000L, EUR, USER_ID);
        existing.complete();

        when(idempotencyService.findDuplicate("idem-key-1")).thenReturn(Optional.of(existing));

        // When
        Transaction result = handler.handle(command(DEST_ID, 3000L));

        // Then
        assertThat(result).isSameAs(existing);
        verifyNoInteractions(walletService, walletRepository, ledgerService, eventPublisher, transactionRepository);
    }


    @Test
    void shouldThrowWhenSourceAndDestinationAreTheSameWallet() {
        // Given
        when(idempotencyService.findDuplicate(any())).thenReturn(Optional.empty());

        // When / Then
        var command = command(SOURCE_ID, 3000L);
        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(InvalidWalletOperationException.class);

        verifyNoInteractions(walletService, walletRepository, ledgerService, eventPublisher, transactionRepository);
    }


    @Test
    void shouldThrowWhenWalletCurrenciesDoNotMatch() {
        // Given
        Wallet source = wallet(USER_ID, EUR, 10_000L);
        Wallet dest   = wallet(UUID.randomUUID(), GBP, 5_000L);

        when(idempotencyService.findDuplicate(any())).thenReturn(Optional.empty());
        when(walletService.getActiveById(SOURCE_ID, USER_ID)).thenReturn(source);
        when(walletRepository.findByIdAndStatus(DEST_ID, WalletStatus.ACTIVE)).thenReturn(Optional.of(dest));

        // When / Then
        var command = command(DEST_ID, 3000L);
        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(CurrencyMismatchException.class);

        verifyNoInteractions(ledgerService, eventPublisher, transactionRepository);
    }


    @Test
    void shouldThrowWhenDestinationWalletNotFoundOrFrozen() {
        // Given
        Wallet source = wallet(USER_ID, EUR, 10_000L);

        when(idempotencyService.findDuplicate(any())).thenReturn(Optional.empty());
        when(walletService.getActiveById(SOURCE_ID, USER_ID)).thenReturn(source);
        when(walletRepository.findByIdAndStatus(DEST_ID, WalletStatus.ACTIVE)).thenReturn(Optional.empty());

        // When / Then
        var command = command(DEST_ID, 3000L);
        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(WalletNotFoundException.class);

        verifyNoInteractions(ledgerService, eventPublisher, transactionRepository);
    }

    @Test
    void shouldThrowInsufficientBalanceWhenBalanceBelowAmount()
    {
        Wallet source = wallet(USER_ID, EUR, 10_000L);
        Wallet dest   = wallet(UUID.randomUUID(), EUR, 5_000L);

        when(idempotencyService.findDuplicate(any())).thenReturn(Optional.empty());
        when(walletService.getActiveById(SOURCE_ID, USER_ID)).thenReturn(source);
        when(walletRepository.findByIdAndStatus(DEST_ID, WalletStatus.ACTIVE)).thenReturn(Optional.of(dest));

        var command = command(DEST_ID, 10_001L);
        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(InsufficientBalanceException.class);
        verifyNoInteractions(ledgerService, eventPublisher, transactionRepository);
    }

    @BeforeEach
    void setUp() {
        handler = new TransferCommandHandler(
                walletService,
                idempotencyService,
                walletRepository,
                transactionRepository,
                ledgerService,
                eventPublisher,
                meterRegistry
        );
    }
}
