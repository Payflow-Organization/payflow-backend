package com.payflow.api.controller;

import com.payflow.api.dto.request.LoginRequest;
import com.payflow.api.dto.request.LogoutRequest;
import com.payflow.api.dto.request.RefreshRequest;
import com.payflow.api.dto.request.RegisterRequest;
import com.payflow.api.dto.response.AuthTokens;
import com.payflow.api.dto.response.UserProfileResponse;
import com.payflow.application.command.auth.LogoutCommandHandler;
import com.payflow.application.command.auth.RefreshCommandHandler;
import com.payflow.application.command.auth.RegisterCommandHandler;
import com.payflow.application.command.auth.LoginCommandHandler;
import com.payflow.application.port.TokenPort;
import com.payflow.application.query.CurrentUserQueryHandler;
import com.payflow.domain.model.user.User;
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

    @PostMapping("/register")
    public AuthTokens register(@Valid @RequestBody RegisterRequest request) {
        return registerCommandHandler.handle(new RegisterCommandHandler.Command(
                request.getEmail(),
                request.getPassword(),
                request.getFullName()
        ));
    }

    @PostMapping("/login")
    public AuthTokens login(@Valid @RequestBody LoginRequest request) {
        return authenticationQueryHandler.handle(new LoginCommandHandler.Command(
                request.getEmail(),
                request.getPassword()
        ));
    }

    @PostMapping("/refresh")
    public AuthTokens refresh(@Valid @RequestBody RefreshRequest request) {
        return refreshCommandHandler.handle(new RefreshCommandHandler.Command(
                request.getRefreshToken()
        ));
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(currentUserQueryHandler.handle(
                new CurrentUserQueryHandler.Query(user.getId())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader("Authorization") String authHeader,
            @RequestBody LogoutRequest request
    ) {
        String rawToken = tokenPort.extractBearerToken(authHeader);
        if (rawToken == null) return ResponseEntity.status(401).build();

        TokenPort.TokenDetails details = tokenPort.extractTokenDetails(rawToken);
        if (details == null) return ResponseEntity.badRequest().build();

        logoutCommandHandler.handle(new LogoutCommandHandler.Command(
                details.jti(),
                details.ttlSeconds(),
                request.refreshToken()
        ));
        return ResponseEntity.noContent().build();
    }
}
