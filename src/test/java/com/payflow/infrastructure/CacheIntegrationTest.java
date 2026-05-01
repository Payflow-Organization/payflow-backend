package com.payflow.infrastructure;

import com.payflow.BaseIntegrationTest;
import com.payflow.application.service.WalletService;
import com.payflow.domain.model.user.User;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.infrastructure.persistence.jpa.LedgerEntryJpaRepository;
import com.payflow.infrastructure.persistence.jpa.TransactionJpaRepository;
import com.payflow.infrastructure.persistence.jpa.UserJpaRepository;
import com.payflow.infrastructure.persistence.jpa.WalletJpaRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;


class CacheIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private WalletService walletService;

    @Autowired
    private WalletJpaRepository walletRepository;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private UUID walletId;
    private UUID userId;

    @Autowired
    private UserJpaRepository userRepository;

    @Autowired
    private TransactionJpaRepository transactionRepository;

    @Autowired
    private LedgerEntryJpaRepository ledgerEntryRepository;

    @BeforeEach
    void setUp() {
        User user = User.builder()
                .email("test-" + UUID.randomUUID() + "@payflow.com")
                .fullName("Test User")
                .passwordHash("test123").build();
        user = userRepository.save(user);
        userId = user.getId();

        Wallet wallet = Wallet.create(userId, Currency.getInstance("EUR"));
        wallet = walletService.save(wallet);
        walletId = wallet.getId();
    }

    @AfterEach
    void tearDown() {
        ledgerEntryRepository.deleteAll();
        transactionRepository.deleteAll();
        walletRepository.deleteAll();
        userRepository.deleteAll();
        cacheManager.getCache("wallets").clear();
    }

    @Test
    void saveEvictsCache() {
        // Given - populate cache
        transactionTemplate.execute(status -> {
            walletService.getActiveById(walletId, userId);
            return null;
        });
        assertNotNull(cacheManager.getCache("wallets").get(walletId + ":" + userId));

        // When - save without going through getActiveById
        transactionTemplate.execute(status -> {
            Wallet wallet = walletRepository.findById(walletId).orElseThrow();
            walletService.save(wallet);
            return null;
        });

        // Then
        assertNull(cacheManager.getCache("wallets").get(walletId + ":" + userId));
    }
}
