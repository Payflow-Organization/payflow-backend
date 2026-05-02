package com.payflow.domain.repository;

import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.model.wallet.WalletStatus;

import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletRepository {
    Optional<Wallet> findByUserIdAndCurrency(UUID userId, Currency currency);
    Optional<Wallet> findByIdAndUserId(UUID id, UUID userId);
    Optional<Wallet> findByUserId(UUID userId);
    List<Wallet> findAllByUserId(UUID userId);
    Optional<Wallet> findByIdAndUserIdAndStatus(UUID id, UUID userId, WalletStatus status);
    Optional<Wallet> findByIdAndStatus(UUID id, WalletStatus status);
    Optional<Wallet> findById(UUID id);
    Wallet save(Wallet wallet);
}