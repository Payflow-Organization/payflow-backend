package com.payflow.domain.repository;

import com.payflow.domain.model.token.RefreshToken;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository {
    Optional<RefreshToken> findByTokenHash(String token);
    List<RefreshToken> findAllByUserId(UUID userId);
    void deleteAllByUserId(UUID userId);
    RefreshToken save(RefreshToken token);
    void revokeAllByUserId(UUID userId);
}