package com.payflow.application.service;

import com.payflow.domain.model.token.InvalidRefreshTokenException;
import com.payflow.domain.model.token.RefreshToken;
import com.payflow.domain.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Slf4j
@Service

public class RefreshTokenService {
    private final long refreshExpirationMs;
    private final RefreshTokenRepository refreshTokenRepository;
    public RefreshTokenService(
            @Value("${app.jwt.refresh-expiration}") long refreshExpirationMs,
            RefreshTokenRepository refreshTokenRepository) {
        this.refreshExpirationMs = refreshExpirationMs;
        this.refreshTokenRepository = refreshTokenRepository;
    }
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Transactional
    public String issue(UUID userId) {
        String token = generateSecureToken();

        refreshTokenRepository.save(RefreshToken.builder()
                .userId(userId)
                .tokenHash(
                        sha256Hex(token)
                )
                .expiresAt(Instant.now().plusMillis(refreshExpirationMs))
                .createdAt(Instant.now())
                .build());
        return token;
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.findByTokenHash(sha256Hex(rawToken))
                .ifPresent(token -> {
                    token.revoke();
                    refreshTokenRepository.save(token);
                    log.warn("Refresh token revoked userId={}", token.getUserId());

                });
    }
    public RefreshToken validate(String rawToken) {
        String hash = sha256Hex(rawToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new InvalidRefreshTokenException("Token not found"));
        if (token.isRevoked()) {
            refreshTokenRepository.revokeAllByUserId(token.getUserId());
            throw new InvalidRefreshTokenException("Token already revoked");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new InvalidRefreshTokenException("Token expired");
        }
        token.validate();
        return token;
    }




    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String generateSecureToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
