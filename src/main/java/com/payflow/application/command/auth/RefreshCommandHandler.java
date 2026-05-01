package com.payflow.application.command.auth;

import com.payflow.api.dto.response.AuthenticationResponse;
import com.payflow.application.port.TokenPort;
import com.payflow.application.port.UserPort;
import com.payflow.application.service.RefreshTokenService;
import com.payflow.domain.model.token.RefreshToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshCommandHandler {
    private final TokenPort tokenPort;
    private final UserPort userPort;
    private final RefreshTokenService refreshTokenService;
    public record Command(String rawRefreshToken) {}
    public AuthenticationResponse handle(Command command)
    {
        RefreshToken token = refreshTokenService.validate(command.rawRefreshToken());
        refreshTokenService.revoke(command.rawRefreshToken());

        UserDetails userDetails = userPort.loadById(token.getUserId());

        String newAccessToken = tokenPort.generateAccessToken(userDetails);
        String newRefreshToken = refreshTokenService.issue(token.getUserId());

        log.info("Refresh token rotated userId={}", token.getUserId());
        return AuthenticationResponse
                .builder()
                .email(userDetails.getUsername())
                .accessToken(newAccessToken)
                .refreshToken(newRefreshToken)
                .build();
    }
}
