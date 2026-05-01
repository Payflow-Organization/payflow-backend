package com.payflow.application.service;

import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.model.wallet.WalletNotFoundException;
import com.payflow.domain.model.wallet.WalletStatus;
import com.payflow.domain.repository.WalletRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {
    private final WalletRepository walletRepository;
    @Cacheable(value = "wallets", key = "#walletId + ':' + #requestingUserId")
    public Wallet getActiveById(UUID walletId, UUID requestingUserId) {
        log.debug("Cache miss — loading wallet from DB walletId={} userId={}", walletId, requestingUserId);
        return walletRepository.findByIdAndUserIdAndStatus(walletId,requestingUserId, WalletStatus.ACTIVE).orElseThrow(
                () -> new WalletNotFoundException(walletId)
        );
    }
    @CacheEvict(value = "wallets", key = "#wallet.id + ':' + #wallet.userId")
    public Wallet save(Wallet wallet) {
        log.debug("Cache evicted walletId={} userId={}", wallet.getId(), wallet.getUserId());
        return walletRepository.save(wallet);
    }
}
