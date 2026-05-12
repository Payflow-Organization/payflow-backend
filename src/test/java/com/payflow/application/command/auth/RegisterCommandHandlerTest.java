package com.payflow.application.command.auth;

import com.payflow.api.dto.response.AuthTokens;
import com.payflow.api.dto.response.AuthenticationResponse;
import com.payflow.application.port.TokenPort;
import com.payflow.application.service.RefreshTokenService;
import com.payflow.application.service.WalletService;
import com.payflow.domain.model.user.EmailAlreadyRegisteredException;
import com.payflow.domain.model.user.User;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.repository.UserRepository;
import com.payflow.domain.repository.WalletRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class RegisterCommandHandlerTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private WalletRepository walletRepository;


    @Mock
    private WalletService walletService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private TokenPort tokenPort;

    @Mock
    private RefreshTokenService refreshTokenService;

    @InjectMocks
    private RegisterCommandHandler handler;

    @Test
    void shouldRegisterUserAndReturnTokens() {
        // Given
        RegisterCommandHandler.Command command = new RegisterCommandHandler.Command(
                "test@payflow.com",
                "password123",
                "Test User"
        );
        when(userRepository.existsByEmail(command.email())).thenReturn(false);
        when(passwordEncoder.encode(any())).thenReturn("hashed_password");
        when(tokenPort.generateAccessToken(any())).thenReturn("access-token");
        when(refreshTokenService.issue(any())).thenReturn("refresh-token");

        // When
        AuthTokens response = handler.handle(command);

        // Then
        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
        assertThat(response.email()).isEqualTo("test@payflow.com");
        verify(userRepository).save(any(User.class));
        verify(walletService).save(any(Wallet.class));
        verify(passwordEncoder).encode("password123");
    }

    @Test
    void shouldThrowWhenEmailAlreadyRegistered() {
        // Given

        RegisterCommandHandler.Command command = new RegisterCommandHandler.Command(
                    "existing@payflow.com",
                "password123",
                "Existing User"
        );
        when(userRepository.existsByEmail(command.email())).thenReturn(true);

        assertThatThrownBy(() -> handler.handle(command))
                .isInstanceOf(EmailAlreadyRegisteredException.class);

        verify(userRepository, never()).save(any(User.class));
        verify(passwordEncoder, never()).encode(any());
    }
}