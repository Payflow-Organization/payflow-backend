package com.payflow.application.command.auth;

import com.payflow.BaseIntegrationTest;
import com.payflow.api.dto.request.RegisterRequest;
import com.payflow.domain.model.token.RefreshToken;
import com.payflow.domain.repository.RefreshTokenRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LogoutIntegrationTest extends BaseIntegrationTest {

    @Autowired private RestTestClient restTestClient;
    @Autowired private RefreshTokenRepository refreshTokenRepository;

    @Test
    void shouldRevokeRefreshTokenOnLogout() {
        String email = "logout-" + UUID.randomUUID() + "@payflow.com";

        var registerResult = restTestClient.post()
                .uri("/api/v1/auth/register")
                .body(RegisterRequest.builder()
                        .email(email)
                        .password("password123")
                        .fullName("Test User")
                        .build())
                .exchange()
                .expectStatus().isNoContent()
                .expectBody(Void.class)
                .returnResult();

        String accessToken = Objects.requireNonNull(
                registerResult.getResponseCookies().getFirst("accessToken")).getValue();
        String refreshToken = Objects.requireNonNull(
                registerResult.getResponseCookies().getFirst("refreshToken")).getValue();

        restTestClient.post()
                .uri("/api/v1/auth/logout")
                .cookie("accessToken", accessToken)
                .cookie("refreshToken", refreshToken)
                .exchange()
                .expectStatus().isNoContent();

        String hash = sha256Hex(refreshToken);
        assertThat(refreshTokenRepository.findByTokenHash(hash))
                .isPresent()
                .get()
                .extracting(RefreshToken::isRevoked)
                .isEqualTo(true);
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
