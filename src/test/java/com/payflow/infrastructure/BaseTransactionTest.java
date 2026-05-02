package com.payflow.infrastructure;

import com.payflow.BaseIntegrationTest;
import com.payflow.application.command.transactions.DepositCommandHandler;
import com.payflow.application.service.WalletService;
import com.payflow.domain.model.user.User;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.repository.LedgerEntryRepository;
import com.payflow.infrastructure.persistence.jpa.TransactionJpaRepository;
import com.payflow.infrastructure.persistence.jpa.UserJpaRepository;
import com.payflow.infrastructure.persistence.jpa.WalletJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Currency;
import java.util.UUID;

public abstract class BaseTransactionTest extends BaseIntegrationTest {

    @Autowired
    UserJpaRepository userRepository;
    @Autowired
    WalletService walletService;
    @Autowired
    DepositCommandHandler depositHandler;

    @Autowired
    LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    TransactionJpaRepository transactionRepository;
    @Autowired
    WalletJpaRepository walletRepository;

    protected User user;
    protected Wallet wallet;

    @BeforeEach
    void setupUserAndWallet() {
        user = userRepository.save(
                User.builder()
                        .fullName("Test User")
                        .email("user-" + UUID.randomUUID() + "@payflow.com")
                        .passwordHash("some-hash")
                        .build()
        );
        wallet = walletService.save(Wallet.create(user.getId(), Currency.getInstance("GBP")));
    }
    @AfterEach
    void tearDown() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();
    }


    protected void seedBalance(long amountCents) {
        depositHandler.handle(new DepositCommandHandler.Command(
                UUID.randomUUID().toString(), wallet.getId(), user.getId(), amountCents
        ));
    }
    protected Wallet freshWallet() {
        User receiver = userRepository.save(
                User.builder()
                        .fullName("Receiver")
                        .email("receiver-" + UUID.randomUUID() + "@payflow.com")
                        .passwordHash("some-hash")
                        .build()
        );
        return walletService.save(Wallet.create(receiver.getId(), Currency.getInstance("GBP")));
    }
}