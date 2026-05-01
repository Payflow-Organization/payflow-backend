package com.payflow.application.command.transactions;

import com.payflow.application.service.IdempotencyService;
import com.payflow.application.service.LedgerService;
import com.payflow.application.service.WalletService;
import com.payflow.domain.model.transaction.CurrencyMismatchException;
import com.payflow.domain.model.transaction.InvalidWalletOperationException;
import com.payflow.domain.model.transaction.Transaction;
import com.payflow.domain.model.transaction.TransactionType;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.model.wallet.WalletNotFoundException;
import com.payflow.domain.model.wallet.WalletStatus;
import com.payflow.domain.repository.TransactionRepository;
import com.payflow.domain.repository.WalletRepository;
import com.payflow.infrastructure.kafka.TransactionOutboxWriter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransferCommandHandler {
    private final WalletService walletService;

    public record Command(
            String idempotencyKey,
            UUID sourceWalletId,
            UUID destinationWalletId,
            UUID requestingUserId,
            long amountCents
    ) {
        public Command {
            if (amountCents <= 0) {
                throw new IllegalArgumentException("Transfer amount must be positive, got: " + amountCents);
            }
        }
    }

    private final IdempotencyService idempotencyService;
    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final TransactionOutboxWriter eventPublisher;
    private final MeterRegistry meterRegistry;

    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class},
            maxAttemptsExpression = "${payflow.retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${payflow.retry.initial-interval-ms:100}",
                    multiplierExpression = "${payflow.retry.multiplier:2.0}",
                    maxDelayExpression = "${payflow.retry.max-interval-ms:1000}"
            )
    )
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public Transaction handle(Command command) {
        // STEP 1: Idempotency
        Timer.Sample timer = Timer.start(meterRegistry);
        String currency = "unknown";
        String path = "duplicate";

        try {
            // STEP 1: Idempotency
            Optional<Transaction> duplicate = idempotencyService.findDuplicate(command.idempotencyKey());
            if (duplicate.isPresent()) {
                meterRegistry.counter("payflow.idempotency.duplicate",
                        "command_type", "transfer"
                ).increment();
                log.warn("Duplicate TRANSFER skipped idempotencyKey={}", command.idempotencyKey());
                return duplicate.get();
            }
            path = "new";
            Transaction tx = processNew(command);
            currency = tx.getCurrency().getCurrencyCode();
            meterRegistry.counter("payflow.transfer.success",
                    "currency", currency
            ).increment();
            return tx;
        }
        finally {
            timer.stop(Timer.builder("payflow.transfer.latency")
                    .tag("currency", currency)
                    .tag("path", path)
                    .register(meterRegistry));
        }
    }

    private Transaction processNew(Command command) {

        // STEP 2: Validate — no self-transfer
        if (command.sourceWalletId().equals(command.destinationWalletId())) {
            throw new InvalidWalletOperationException(command.sourceWalletId());
        }

        // STEP 3: Load both wallets — only the source needs an ownership check
        Wallet sourceWallet = walletService.getActiveById(command.sourceWalletId(), command.requestingUserId());
        Wallet destinationWallet = walletRepository.findByIdAndStatus(command.destinationWalletId(), WalletStatus.ACTIVE)
                .orElseThrow(() -> new WalletNotFoundException(command.destinationWalletId()));
        if (!sourceWallet.getCurrency().equals(destinationWallet.getCurrency())) {
            throw new CurrencyMismatchException(sourceWallet.getCurrency(), destinationWallet.getCurrency());
        }

        // STEP 4: Create a PENDING transaction record
        Transaction tx = Transaction.create(
                command.idempotencyKey(),
                TransactionType.TRANSFER,
                command.sourceWalletId(),
                command.destinationWalletId(),
                command.amountCents(),
                sourceWallet.getCurrency(),
                command.requestingUserId()
        );
        tx = idempotencyService.deduplicateOrSave(tx);

        // STEP 5: Validate source balance before touching the ledger
        sourceWallet.validateSufficientBalance(command.amountCents());

        // STEP 6: Debit source — ledger first, then mutate cached balance
        ledgerService.createDebitEntry(tx, sourceWallet, command.amountCents());
        sourceWallet.debit(command.amountCents());
        walletService.save(sourceWallet);

        // STEP 7: Credit destination
        ledgerService.createCreditEntry(tx, destinationWallet, command.amountCents());
        destinationWallet.credit(command.amountCents());
        walletService.save(destinationWallet);

        // STEP 8: Mark complete and persist
        tx.complete();
        eventPublisher.publishTransactionCreated(tx,sourceWallet.getUserId());
        tx= transactionRepository.save(tx);
        log.info("Transfer completed from fromWalletId={} to toWalletId={} amountCents={} txId={} idempotencyKey={}",
                command.sourceWalletId(),command.destinationWalletId(), command.amountCents(), tx.getId(), command.idempotencyKey());
        return tx;
    }
}
