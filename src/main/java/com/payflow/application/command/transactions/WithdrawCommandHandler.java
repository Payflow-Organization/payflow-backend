package com.payflow.application.command.transactions;


import com.payflow.application.service.IdempotencyService;
import com.payflow.application.service.LedgerService;
import com.payflow.application.service.WalletService;
import com.payflow.domain.model.transaction.Transaction;
import com.payflow.domain.model.transaction.TransactionType;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.repository.TransactionRepository;
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
public class WithdrawCommandHandler {

    private final WalletService walletService;
    private final IdempotencyService idempotencyService;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final TransactionOutboxWriter eventPublisher;
    private final MeterRegistry meterRegistry;

    public record Command(
            String idempotencyKey,
            UUID walletId,
            UUID requestingUserId,
            long amountCents
    ) {
        public Command {
            if (amountCents <= 0) {
                throw new IllegalArgumentException("Withdraw amount must be positive, got: " + amountCents);
            }
        }
    }



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
        System.out.println("meterRegistry = " + meterRegistry);
        Timer.Sample timer = Timer.start(meterRegistry);
        String path = "duplicate";
        String currency = "unknown";
        try{
            // STEP 1: Idempotency
            Optional<Transaction> duplicate = idempotencyService.findDuplicate(command.idempotencyKey());
            if (duplicate.isPresent()) {
                meterRegistry.counter("payflow.idempotency.duplicate",
                        "command_type", "withdraw");
                log.warn("Duplicate WITHDRAW skipped idempotencyKey={} walletId={}",
                        command.idempotencyKey(), command.walletId());
                return duplicate.get();
            }
            path = "new";
            Transaction tx = processNew(command);
            currency = tx.getCurrency().getCurrencyCode();
            meterRegistry.counter("payflow.transfer.success",
                    "currency", currency);
            return tx;
        }
        finally{
            timer.stop(Timer.builder("payflow.withdraw.latency")
                    .tag("currency", currency)
                    .tag("path", path)
                    .register(meterRegistry));
        }
    }

    private Transaction processNew(Command command) {
        // STEP 2: Load & validate wallet — ownership + active status
        Wallet wallet = walletService.getActiveById(command.walletId(),command.requestingUserId());


        // STEP 3: Create a PENDING transaction record
        Transaction tx = Transaction.create(
                command.idempotencyKey(),
                TransactionType.WITHDRAW,
                command.walletId(),
                null,
                command.amountCents(),
                wallet.getCurrency(),
                command.requestingUserId()
        );
        // Handles concurrent duplicate race — unique constraint catches the second insert,
        // deduplicateOrSave recovers by returning the existing transaction.
        tx = idempotencyService.deduplicateOrSave(tx);

        // STEP 4: Validate balance before touching the ledger
        wallet.validateSufficientBalance(command.amountCents());

        // STEP 5: Ledger entry — balanceAfter calculated before cache is mutated
        ledgerService.createDebitEntry(tx, wallet, command.amountCents());

        // STEP 6: Debit cached balance after the ledger is written
        wallet.debit(command.amountCents());
        walletService.save(wallet);

        // STEP 7: Mark complete and persist
        tx.complete();
        eventPublisher.publishTransactionCreated(tx,wallet.getUserId());
        tx = transactionRepository.save(tx);
        log.info("Withdraw completed walletId={} amountCents={} txId={} idempotencyKey={}",
                command.walletId(), command.amountCents(), tx.getId(), command.idempotencyKey());
        return tx;
    }
}
