package com.payflow.application.query;

import com.payflow.api.dto.response.TransactionResponse;
import com.payflow.domain.model.transaction.TransactionNotFoundException;
import com.payflow.domain.model.transaction.TransactionStatus;
import com.payflow.domain.model.transaction.TransactionType;
import com.payflow.domain.model.wallet.WalletNotFoundException;
import com.payflow.domain.repository.TransactionRepository;
import com.payflow.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransactionQueryHandler {

    public record GetTransactionsQuery(UUID userId,
                                       UUID walletId,          // null = all wallets
                                       TransactionType type,   // null = all types
                                       TransactionStatus status, // null = all statuses
                                       Pageable pageable) {}
    public record GetTransactionQuery(UUID transactionId, UUID userId) {}

    private final TransactionRepository repository;
    private final WalletRepository walletRepository;

    @Transactional(readOnly = true)
    public Page<TransactionResponse> handle(GetTransactionsQuery query) {
        if (query.walletId() == null) {
            throw new IllegalArgumentException("walletId is required for transaction queries");
        }

        walletRepository.findByIdAndUserId(query.walletId(), query.userId())
                .orElseThrow(() -> new WalletNotFoundException(query.walletId()));

        return repository
                .findFiltered(
                        query.walletId(),
                        query.type(),
                        query.status(),
                        query.pageable())
                .map(TransactionResponse::from);
    }

    @Transactional(readOnly = true)
    public TransactionResponse handle(GetTransactionQuery query) {
        return TransactionResponse.from(
                repository.findByIdAndUserId(query.transactionId(), query.userId())
                .orElseThrow(() ->
                        new TransactionNotFoundException(query.transactionId())));
    }

}