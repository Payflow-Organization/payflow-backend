package com.payflow.infrastructure;

import com.payflow.api.dto.request.RegisterRequest;
import com.payflow.api.dto.response.AuthTokens;
import com.payflow.infrastructure.persistence.jpa.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;


class ExportIntegrationTest extends BaseTransactionTest {

    @Autowired RestTestClient restTestClient;
    @Autowired AuditLogRepository auditLogRepository;

    private String token;

    @BeforeEach
    @Override
    void setupUserAndWallet() {
        String email = "export-" + UUID.randomUUID() + "@payflow.com";

        token = restTestClient.post()
                .uri("/api/v1/auth/register")
                .body(RegisterRequest.builder()
                        .email(email)
                        .password("password123")
                        .fullName("Export User")
                        .build())
                .exchange()
                .expectStatus().isOk()
                .returnResult(AuthTokens.class)
                .getResponseBody()
                .accessToken();

        user = userRepository.findByEmail(email).orElseThrow();
        wallet = walletRepository.findByUserId(user.getId()).stream()
                .filter(w -> w.getCurrency().equals(Currency.getInstance("GBP")))
                .findFirst()
                .orElseThrow();
    }

    @AfterEach
    @Override
    void tearDown() {
        auditLogRepository.deleteAll();
        super.tearDown();
    }

    @Test
    void shouldReturnPdfWhenTransactionsExist() {
        // Given
        seedBalance(10_000L);

        // When / Then
        restTestClient.get()
                .uri("/api/v1/analytics/{walletId}/export?format=PDF&from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z",
                        wallet.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_PDF_VALUE)
                .expectHeader().valueMatches(HttpHeaders.CONTENT_DISPOSITION, ".*attachment.*")
                .expectBody(byte[].class)
                .consumeWith(result -> assertThat(result.getResponseBody()).isNotEmpty());
    }

    @Test
    void shouldReturnCsvWhenTransactionsExist() {
        // Given
        seedBalance(10_000L);

        // When / Then
        restTestClient.get()
                .uri("/api/v1/analytics/{walletId}/export?format=CSV&from=2026-01-01T00:00:00Z&to=2026-12-31T23:59:59Z",
                        wallet.getId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("text/csv")
                .expectHeader().valueMatches(HttpHeaders.CONTENT_DISPOSITION, ".*attachment.*")
                .expectBody(byte[].class)
                .consumeWith(result -> assertThat(result.getResponseBody()).isNotEmpty());
    }
}