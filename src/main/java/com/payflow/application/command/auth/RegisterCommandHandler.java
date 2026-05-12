// introduce real DB writes — currently stateless so side effects are minimal.

package com.payflow.application.command.auth;

import com.payflow.api.dto.response.AuthTokens;
import com.payflow.api.dto.response.AuthenticationResponse;
import com.payflow.application.port.TokenPort;
import com.payflow.application.service.RefreshTokenService;
import com.payflow.application.service.WalletService;
import com.payflow.domain.model.user.EmailAlreadyRegisteredException;
import com.payflow.domain.model.user.User;
import com.payflow.domain.model.user.UserStatus;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.repository.RefreshTokenRepository;
import com.payflow.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;

@Slf4j
@Service
@RequiredArgsConstructor
public class RegisterCommandHandler {

    private final RefreshTokenService refreshTokenService;

    public record Command(String email, String password, String fullName) {}

    private final UserRepository userRepository;
    private final WalletService walletService;
    private final PasswordEncoder passwordEncoder;
    private final TokenPort tokenPort;

    @Transactional // For future use, convention wise atm
    public AuthTokens handle(Command command) {
        if (userRepository.existsByEmail(command.email())) {
            throw new EmailAlreadyRegisteredException(command.email());
        }
        User user = User.builder()
                .fullName(command.fullName())
                .status(UserStatus.ACTIVE)
                .email(command.email())
                .passwordHash(passwordEncoder.encode(command.password()))
                .build();
        userRepository.save(user);

        Wallet wallet = Wallet.create(user.getId(), Currency.getInstance("GBP"));
        walletService.save(wallet);
        String accessToken = tokenPort.generateAccessToken(user);
        String rawRefreshToken = refreshTokenService.issue(user.getId());
        log.info("User logged in userId={} walletID={}", user.getId(), wallet.getId());
        return new AuthTokens(
                accessToken,
                rawRefreshToken,
                command.email()
        );
    }
}
