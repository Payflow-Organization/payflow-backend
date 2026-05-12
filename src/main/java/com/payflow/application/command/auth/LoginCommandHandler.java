package com.payflow.application.command.auth;


import com.payflow.api.dto.response.AuthTokens;
import com.payflow.api.dto.response.AuthenticationResponse;
import com.payflow.application.port.TokenPort;
import com.payflow.application.service.RefreshTokenService;
import com.payflow.domain.model.user.User;
import com.payflow.domain.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class LoginCommandHandler {

    public record Command(String email, String password) {}

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final TokenPort tokenPort;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public AuthTokens handle(Command command)
    {
        authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(
                command.email(),
                command.password()
        ));
        User user = userRepository.findByEmail(command.email()).orElseThrow(()->
                new UsernameNotFoundException("User not found: "+ command.email()));
        String accessToken= tokenPort.generateAccessToken(user);
        String rawRefreshToken= refreshTokenService.issue(user.getId());

        log.info("User logged in userId={} email={}", user.getId(), user.getUsername());
        return new AuthTokens(
                accessToken,
                rawRefreshToken,
                command.email()
        );
    }


}
