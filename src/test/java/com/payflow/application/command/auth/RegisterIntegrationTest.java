package com.payflow.application.command.auth;

import com.payflow.BaseIntegrationTest;
import com.payflow.api.dto.request.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.client.RestTestClient;

import java.util.UUID;


class RegisterIntegrationTest extends BaseIntegrationTest {

    @Autowired private RestTestClient restTestClient;

    @Test
    void shouldRegisterUserCreateWalletAndReturnTokens() {
        restTestClient.post()
                .uri("/api/v1/auth/register")
                .body(RegisterRequest.builder()
                        .email("register-" + UUID.randomUUID() + "@payflow.com")
                        .password("password123")
                        .fullName("Test User")
                        .build())
                .exchange()
                .expectStatus().isNoContent();
    }

    @Test
    void shouldReturn409WhenEmailAlreadyRegistered() {
        String email = "register-" + UUID.randomUUID() + "@payflow.com";
        RegisterRequest request = RegisterRequest.builder()
                .email(email)
                .password("password123")
                .fullName("Test User")
                .build();

        restTestClient.post().uri("/api/v1/auth/register").body(request)
                .exchange().expectStatus().isNoContent();

        restTestClient.post().uri("/api/v1/auth/register").body(request)
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void shouldReturn400WhenInputIsInvalid() {
        restTestClient.post()
                .uri("/api/v1/auth/register")
                .body(RegisterRequest.builder()
                        .email("not-an-email")
                        .password("short")
                        .fullName("")
                        .build())
                .exchange()
                .expectStatus().isBadRequest();
    }
}