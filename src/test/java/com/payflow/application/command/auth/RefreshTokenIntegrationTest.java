package com.payflow.application.command.auth;

import com.payflow.BaseIntegrationTest;
import com.payflow.api.dto.request.RegisterRequest;
import com.payflow.domain.model.token.RefreshToken;
import com.payflow.domain.repository.RefreshTokenRepository;
import com.payflow.infrastructure.persistence.jpa.UserJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenIntegrationTest extends BaseIntegrationTest {
    @Autowired private RestTestClient restTestClient;
    @Autowired private RefreshTokenRepository refreshTokenRepository;
    @Autowired private UserJpaRepository userRepository;

    private String rawRefreshToken;
    private String userEmail;

    @BeforeEach
    void setUp() {
        userEmail = "refresh-" + UUID.randomUUID() + "@payflow.com";

        var result = restTestClient.post()
                .uri("/api/v1/auth/register")
                .body(RegisterRequest.builder()
                        .email(userEmail)
                        .password("password123")
                        .fullName("Test User")
                        .build())
                .exchange()
                .expectStatus().isNoContent()
                .expectBody(Void.class)
                .returnResult();

        rawRefreshToken = Objects.requireNonNull(
                result.getResponseCookies().getFirst("refreshToken")).getValue();
    }

    @Test
    void shouldRotateTokenOnValidRefresh() {
        restTestClient.post()
                .uri("/api/v1/auth/refresh")
                .cookie("refreshToken", rawRefreshToken)
                .exchange()
                .expectStatus().isNoContent();

        String hash = sha256Hex(rawRefreshToken);
        assertThat(refreshTokenRepository.findByTokenHash(hash))
                .isPresent()
                .get()
                .extracting(RefreshToken::isRevoked)
                .isEqualTo(true);
    }

    @Test
    void shouldReturn401OnReplayAttack() {
        restTestClient.post()
                .uri("/api/v1/auth/refresh")
                .cookie("refreshToken", rawRefreshToken)
                .exchange()
                .expectStatus().isNoContent();

        restTestClient.post()
                .uri("/api/v1/auth/refresh")
                .cookie("refreshToken", rawRefreshToken)
                .exchange()
                .expectStatus().isUnauthorized();

        var user = userRepository.findByEmail(userEmail).orElseThrow();
        assertThat(refreshTokenRepository.findAllByUserId(user.getId()))
                .allMatch(RefreshToken::isRevoked);
    }

    @Test
    void shouldReturn401OnExpiredToken() {
        String expiredRaw = UUID.randomUUID().toString();
        refreshTokenRepository.save(RefreshToken.builder()
                .userId(userRepository.findByEmail(userEmail).orElseThrow().getId())
                .tokenHash(sha256Hex(expiredRaw))
                .expiresAt(Instant.now().minus(1, ChronoUnit.DAYS))
                .createdAt(Instant.now())
                .build());

        restTestClient.post()
                .uri("/api/v1/auth/refresh")
                .cookie("refreshToken", expiredRaw)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldReturn401OnRevokedToken() {
        String hash = sha256Hex(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(hash)
                .ifPresent(token -> {
                    token.revoke();
                    refreshTokenRepository.save(token);
                });

        restTestClient.post()
                .uri("/api/v1/auth/refresh")
                .cookie("refreshToken", rawRefreshToken)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldReturn401OnMalformedToken() {
        restTestClient.post()
                .uri("/api/v1/auth/refresh")
                .cookie("refreshToken", "malformed-token")
                .exchange()
                .expectStatus().isUnauthorized();
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
}
