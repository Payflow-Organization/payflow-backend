package com.payflow.application.command.auth;

import com.payflow.api.dto.response.AuthTokens;
import com.payflow.api.dto.response.AuthenticationResponse;
import com.payflow.application.port.TokenPort;
import com.payflow.application.port.UserPort;
import com.payflow.application.service.RefreshTokenService;
import com.payflow.domain.model.token.InvalidRefreshTokenException;
import com.payflow.domain.model.token.RefreshToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RefreshCommandHandlerTest {

    @Mock private RefreshTokenService refreshTokenService;
    @Mock private TokenPort tokenPort;
    @Mock private UserPort userPort;

    @InjectMocks
    private RefreshCommandHandler handler;

    @Test
    void shouldRevokeOldAndIssueNewTokensOnValidRefresh() {
        // Given
        UUID userId = UUID.randomUUID();
        RefreshToken token = RefreshToken.builder()
                .userId(userId)
                .tokenHash("hash")
                .expiresAt(Instant.now().plusSeconds(3600))
                .revoked(false)
                .build();

        UserDetails userDetails = mock(UserDetails.class);
        when(userDetails.getUsername()).thenReturn("user@payflow.com");
        when(refreshTokenService.validate("old-token")).thenReturn(token);
        when(userPort.loadById(userId)).thenReturn(userDetails);
        when(tokenPort.generateAccessToken(userDetails)).thenReturn("new-access");
        when(refreshTokenService.issue(userId)).thenReturn("new-refresh");

        // When
        AuthTokens response = handler.handle(new RefreshCommandHandler.Command("old-token"));

        // Then
        assertThat(response.accessToken()).isEqualTo("new-access");
        assertThat(response.refreshToken()).isEqualTo("new-refresh");
        verify(refreshTokenService).revoke("old-token");
    }

    @Test
    void shouldPropagateExceptionWhenTokenInvalid() {
        // Given
        when(refreshTokenService.validate(any()))
                .thenThrow(new InvalidRefreshTokenException("Token not found"));

        // When + Then
        var command = new RefreshCommandHandler.Command("bad-token");
        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenService, never()).revoke(any());
        verify(refreshTokenService, never()).issue(any());
    }
}
