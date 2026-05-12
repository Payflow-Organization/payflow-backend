package com.payflow.application.command.auth;

import com.payflow.api.dto.response.AuthTokens;
import com.payflow.api.dto.response.AuthenticationResponse;
import com.payflow.application.port.TokenPort;
import com.payflow.application.service.RefreshTokenService;
import com.payflow.domain.model.user.User;
import com.payflow.domain.model.user.UserStatus;
import com.payflow.domain.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoginCommandHandlerTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TokenPort tokenPort;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private LoginCommandHandler handler;

    @Test
    void shouldLoginAndReturnTokens() {
        // Given
        LoginCommandHandler.Command command = new LoginCommandHandler.Command("test@payflow.com", "password123");

        User user = User.builder()
                .email("test@payflow.com")
                .passwordHash("hashed")
                .fullName("Test User")
                .status(UserStatus.ACTIVE)
                .build();

        when(userRepository.findByEmail(command.email())).thenReturn(Optional.of(user));
        when(tokenPort.generateAccessToken(any())).thenReturn("access-token");
        when(refreshTokenService.issue(any())).thenReturn("refresh-token");

        // When
        AuthTokens response = handler.handle(command);

        // Then
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        verify(authenticationManager).authenticate(any());
    }

    @Test
    void shouldThrowWhenUserNotFoundDuringLogin() {
        // Given
        LoginCommandHandler.Command query = new LoginCommandHandler.Command("unknown@payflow.com", "password123");

        when(userRepository.findByEmail(query.email())).thenReturn(Optional.empty());

        // When + Then
        assertThatThrownBy(() -> handler.handle(query))
                .isInstanceOf(UsernameNotFoundException.class);
    }
}