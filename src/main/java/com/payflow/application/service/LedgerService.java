package com.payflow.application.service;

import com.payflow.domain.model.ledger.EntryType;
import com.payflow.domain.model.ledger.LedgerEntry;
import com.payflow.domain.model.transaction.Transaction;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.repository.LedgerEntryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class LedgerService {

    private final LedgerEntryRepository ledgerEntryRepository;

    // ADR-015: double-entry — every money movement produces both a credit and a debit entry
    public void createCreditEntry(Transaction tx, Wallet wallet, long amountCents) {
        long balanceAfter = wallet.getCurrentBalance() + amountCents;
        ledgerEntryRepository.save(LedgerEntry.builder()
                .transactionId(tx.getId())
                .walletId(wallet.getId())
                .entryType(EntryType.CREDIT)
                .amount(amountCents)
                .balanceAfter(balanceAfter)
                .build());
    }

    public void createDebitEntry(Transaction tx, Wallet wallet, long amountCents) {
        long balanceAfter = wallet.getCurrentBalance() - amountCents;
        ledgerEntryRepository.save(LedgerEntry.builder()
                .transactionId(tx.getId())
                .walletId(wallet.getId())
                .entryType(EntryType.DEBIT)
                .amount(amountCents)
                .balanceAfter(balanceAfter)
                .build());
    }
}