package com.payflow.api.controller;

import com.payflow.api.dto.request.TransactionRequest;
import com.payflow.api.dto.response.TransactionResponse;
import com.payflow.application.command.transactions.DepositCommandHandler;
import com.payflow.application.command.transactions.TransferCommandHandler;
import com.payflow.application.command.transactions.WithdrawCommandHandler;
import com.payflow.application.query.TransactionQueryHandler;
import com.payflow.domain.model.transaction.TransactionStatus;
import com.payflow.domain.model.transaction.TransactionType;
import com.payflow.domain.model.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/transactions")
public class TransactionController {
    private final TransactionQueryHandler transactionQueryHandler;
    private final DepositCommandHandler depositCommandHandler;
    private final WithdrawCommandHandler withdrawCommandHandler;
    private final TransferCommandHandler transferCommandHandler;


    @GetMapping
    public Page<TransactionResponse> getTransactions(
            Pageable pageable,
            @RequestParam(required = false) UUID walletId,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(required = false) TransactionStatus status,
            @AuthenticationPrincipal User user) {
        return transactionQueryHandler
                .handle(new TransactionQueryHandler
                        .GetTransactionsQuery(user.getId(), walletId, type, status, pageable));
    }

    @GetMapping("/{id}")
    public TransactionResponse getTransaction(@PathVariable UUID id, @AuthenticationPrincipal User user)
    {
        return transactionQueryHandler.handle(new TransactionQueryHandler.GetTransactionQuery(id, user.getId()));
    }

    @PostMapping("/deposit")
    public ResponseEntity<TransactionResponse> deposit(
            @RequestBody @Valid TransactionRequest.DepositRequest request,
            @AuthenticationPrincipal User user)
    {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TransactionResponse.from(depositCommandHandler.handle(
                        new DepositCommandHandler.Command(
                                request.idempotencyKey().toString(),
                                request.toWalletId(),
                                user.getId(),
                                request.amount()
                        )
                        )
                ));
    }
    @PostMapping("/withdraw")
    public ResponseEntity<TransactionResponse> withdraw(
            @RequestBody @Valid TransactionRequest.WithdrawRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TransactionResponse.from(withdrawCommandHandler.handle(
                        new WithdrawCommandHandler.Command(
                                request.idempotencyKey().toString(),
                                request.fromWalletId(),
                                user.getId(),
                                request.amount()
                        ))));
    }
    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @RequestBody @Valid TransactionRequest.TransferRequest request,
            @AuthenticationPrincipal User user) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(TransactionResponse.from(transferCommandHandler.handle(
                        new TransferCommandHandler.Command(
                                request.idempotencyKey().toString(),
                                request.fromWalletId(),
                                request.toWalletId(),
                                user.getId(),
                                request.amount()
                        ))));
    }
}
