package com.payflow.api.controller;

import com.payflow.api.dto.request.LoginRequest;
import com.payflow.api.dto.request.RegisterRequest;
import com.payflow.api.dto.response.AuthTokens;
import com.payflow.api.dto.response.AuthenticationResponse;
import com.payflow.api.dto.response.UserProfileResponse;
import com.payflow.application.command.auth.LogoutCommandHandler;
import com.payflow.application.command.auth.RefreshCommandHandler;
import com.payflow.application.command.auth.RegisterCommandHandler;
import com.payflow.application.command.auth.LoginCommandHandler;
import com.payflow.application.port.TokenPort;
import com.payflow.application.query.CurrentUserQueryHandler;
import com.payflow.domain.model.user.User;
import com.payflow.infrastructure.web.CookieService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RegisterCommandHandler registerCommandHandler;
    private final LoginCommandHandler authenticationQueryHandler;
    private final LogoutCommandHandler logoutCommandHandler;
    private final RefreshCommandHandler refreshCommandHandler;
    private final CurrentUserQueryHandler currentUserQueryHandler;
    private final TokenPort tokenPort;
    private final CookieService cookieService;

    @PostMapping("/register")
    public AuthenticationResponse register(@Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
        AuthTokens auth = registerCommandHandler.handle(new RegisterCommandHandler.Command(
                request.getEmail(),
                request.getPassword(),
                request.getFullName()
        ));
        cookieService.setTokenCookies(response, auth.accessToken(), auth.refreshToken());
        return new AuthenticationResponse(auth.email());
    }

    @PostMapping("/login")
    public AuthenticationResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AuthTokens auth = authenticationQueryHandler.handle(new LoginCommandHandler.Command(
                request.getEmail(),
                request.getPassword()
        ));
        cookieService.setTokenCookies(response, auth.accessToken(), auth.refreshToken());
        return new AuthenticationResponse(auth.email());
    }

    @PostMapping("/refresh")
    public  ResponseEntity<Void> refresh(@CookieValue("refreshToken") String refreshToken,
                                          HttpServletResponse response) {
        AuthTokens auth = refreshCommandHandler.handle(
                new RefreshCommandHandler.Command(refreshToken)
        );
        cookieService.setTokenCookies(response, auth.accessToken(), auth.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(currentUserQueryHandler.handle(
                new CurrentUserQueryHandler.Query(user.getId())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue("accessToken") String accessToken,
            @CookieValue("refreshToken") String refreshToken,
            HttpServletResponse response
    ) {
        TokenPort.TokenDetails details = tokenPort.extractTokenDetails(accessToken);
        if (details == null) return ResponseEntity.status(401).build();

        logoutCommandHandler.handle(new LogoutCommandHandler.Command(
                details.jti(),
                details.ttlSeconds(),
                refreshToken
        ));
        cookieService.removeTokenCookies(response);
        return ResponseEntity.noContent().build();
    }
}
