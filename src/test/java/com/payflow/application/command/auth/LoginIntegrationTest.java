package com.payflow.application.command.auth;


import com.payflow.BaseIntegrationTest;
import com.payflow.api.dto.request.LoginRequest;
import com.payflow.api.dto.request.RegisterRequest;
import com.payflow.domain.repository.UserRepository;
import com.payflow.infrastructure.persistence.jpa.WalletJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.UUID;

class LoginIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RestTestClient restTestClient;
    @Autowired private UserRepository userRepository;
    @Autowired private WalletJpaRepository walletRepository;

    private String userEmail;

    @BeforeEach
    void setUp() {
        userEmail = "login-" + UUID.randomUUID() + "@payflow.com";
        restTestClient.post()
                .uri("/api/v1/auth/register")
                .body(RegisterRequest.builder()
                        .email(userEmail)
                        .password("password123")
                        .fullName("Test User")
                        .build())
                .exchange()
                .expectStatus().isNoContent();
    }


    @Test
    void shouldLoginUserAndReturnTokens() {

        // When
        restTestClient.post()
                .uri("/api/v1/auth/login")
                .body(LoginRequest.builder()
                        .email(userEmail)
                        .password("password123")
                        .build()
                )
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void shouldReturn401WithWrongPassword() {

        restTestClient.post()
                .uri("/api/v1/auth/login")
                .body(LoginRequest.builder()
                        .email(userEmail)
                        .password("wrongpassword")
                        .build())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void shouldReturn401WithWrongEmail() {

        restTestClient.post()
                .uri("/api/v1/auth/login")
                .body(LoginRequest.builder()
                        .email("nonexistent@payflow.com")
                        .password("password123")
                        .build())
                .exchange()
                .expectStatus().isUnauthorized();
    }

}
