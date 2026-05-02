package com.payflow.infrastructure.security;

import com.payflow.application.port.TokenPort;
import com.payflow.domain.model.user.User;
import com.payflow.domain.model.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import static org.assertj.core.api.Assertions.assertThat;
import org.springframework.test.util.ReflectionTestUtils;


class JwtServiceTest {

    private JwtService jwtService;

    private UserDetails userDetails;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService();
        ReflectionTestUtils.setField(jwtService, "jwtSecret",
                "dGVzdC1zZWNyZXQta2V5LW11c3QtYmUtYXQtbGVhc3QtMjU2LWJpdHMtbG9uZwo=");
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", 900000L);

        userDetails = User.builder()
                .email("test@payflow.com")
                .passwordHash("hashed")
                .fullName("Test User")
                .status(UserStatus.ACTIVE)
                .build();
    }

    @Test
    void shouldGenerateValidTokenAndExtractUsername() {
        String token = jwtService.generateAccessToken(userDetails);

        assertThat(jwtService.validateToken(token, userDetails)).isTrue();
        assertThat(jwtService.extractUsername(token)).isEqualTo("test@payflow.com");
    }

    @Test
    void shouldReturnFalseForTamperedToken() {
        String token = jwtService.generateAccessToken(userDetails);
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";

        assertThat(jwtService.validateToken(tampered, userDetails)).isFalse();
    }

    @Test
    void shouldReturnFalseForExpiredToken() {
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", -1000L);
        String token = jwtService.generateAccessToken(userDetails);

        assertThat(jwtService.validateToken(token, userDetails)).isFalse();
    }

    @Test
    void shouldExtractTokenDetailsFromValidBearerToken() {
        String token = jwtService.generateAccessToken(userDetails);

        TokenPort.TokenDetails details = jwtService.extractTokenDetails("Bearer " + token);

        assertThat(details).isNotNull();
        assertThat(details.jti()).isNotNull();
        assertThat(details.ttlSeconds()).isPositive();
    }

    @Test
    void shouldReturnNullForNullBearerToken() {
        TokenPort.TokenDetails details = jwtService.extractTokenDetails(null);

        assertThat(details).isNull();
    }

    @Test
    void shouldReturnNullForMissingBearerPrefix() {
        TokenPort.TokenDetails details = jwtService.extractTokenDetails("notavalidbearertoken");

        assertThat(details).isNull();
    }

    @Test
    void shouldReturnZeroTtlForExpiredToken() {
        ReflectionTestUtils.setField(jwtService, "jwtExpiration", -1000L);
        String token = jwtService.generateAccessToken(userDetails);

        TokenPort.TokenDetails details = jwtService.extractTokenDetails("Bearer " + token);

        assertThat(details).isNotNull();
        assertThat(details.ttlSeconds()).isZero();
    }
}