package com.payflow.application.command.transactions;

import com.payflow.application.service.IdempotencyService;
import com.payflow.application.service.LedgerService;
import com.payflow.application.service.WalletService;
import com.payflow.domain.model.transaction.Transaction;
import com.payflow.domain.model.transaction.TransactionType;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.repository.TransactionRepository;
import com.payflow.infrastructure.kafka.TransactionOutboxWriter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepositCommandHandler {

    private final WalletService walletService;

    public record Command(
            String idempotencyKey,
            UUID walletId,
            UUID requestingUserId,
            long amountCents
    ) {
        public Command {
            if (amountCents <= 0) {
                throw new IllegalArgumentException("Deposit amount must be positive, got: " + amountCents);
            }
        }
    }

    private final IdempotencyService idempotencyService;
    private final TransactionRepository transactionRepository;
    private final LedgerService ledgerService;
    private final TransactionOutboxWriter eventPublisher;

    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class,PessimisticLockingFailureException .class},
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
        return idempotencyService.findDuplicate(command.idempotencyKey())
                .map(tx-> {
                    log.warn("Duplicate DEPOSIT skipped idempotencyKey={} walletId={}",
                            command.idempotencyKey(), command.walletId());
                    return tx;
                })
                .orElseGet(() -> processNew(command));
    }

    private Transaction processNew(Command command) {
        // STEP 2: Load & validate wallet — ownership + active status
        Wallet wallet = walletService.getActiveById(command.walletId(), command.requestingUserId());

        // STEP 3: No balance check for deposit

        // STEP 4: Create a PENDING transaction record
        Transaction tx = Transaction.create(
                command.idempotencyKey(),
                TransactionType.DEPOSIT,
                null,
                command.walletId(),
                command.amountCents(),
                wallet.getCurrency(),
                command.requestingUserId()
        );
        tx = idempotencyService.deduplicateOrSave(tx);

        // STEP 5: Ledger entry first (source of truth)
        ledgerService.createCreditEntry(tx, wallet, command.amountCents());

        // STEP 6: Update cached balance after the ledger is written
        wallet.credit(command.amountCents());
        walletService.save(wallet);

        // STEP 7: Mark complete and persist
        tx.complete();
        eventPublisher.publishTransactionCreated(tx,wallet.getUserId());
        tx= transactionRepository.save(tx);
        log.info("Deposit completed walletId={} amountCents={} txId={} idempotencyKey={}",
                command.walletId(), command.amountCents(), tx.getId(), command.idempotencyKey());
        return tx;
    }
}
