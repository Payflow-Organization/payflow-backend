package com.payflow.api.controller;

import com.payflow.api.dto.request.LoginRequest;
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
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final RegisterCommandHandler registerCommandHandler;
    private final LoginCommandHandler loginCommandHandler;
    private final LogoutCommandHandler logoutCommandHandler;
    private final RefreshCommandHandler refreshCommandHandler;
    private final CurrentUserQueryHandler currentUserQueryHandler;
    private final TokenPort tokenPort;

    @Value("${app.jwt.expiration}")
    private long accessTokenExpiryMs;

    @Value("${app.jwt.refresh-expiration}")
    private long refreshTokenExpiryMs;

    @Value("${app.cookie.secure}")
    private boolean cookieSecure;

    @Value("${app.cookie.same-site}")
    private String cookieSameSite;

    @PostMapping("/register")
    public ResponseEntity<Void> register(@Valid @RequestBody RegisterRequest request, HttpServletResponse response) {
        AuthTokens tokens = registerCommandHandler.handle(new RegisterCommandHandler.Command(
                request.getEmail(), request.getPassword(), request.getFullName()
        ));
        addTokenCookies(response, tokens);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/login")
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        AuthTokens tokens = loginCommandHandler.handle(new LoginCommandHandler.Command(
                request.getEmail(), request.getPassword()
        ));
        addTokenCookies(response, tokens);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<Void> refresh(
            @CookieValue(value = "refreshToken", required = false) String rawRefreshToken,
            HttpServletResponse response) {
        if (rawRefreshToken == null) return ResponseEntity.status(401).build();
        AuthTokens tokens = refreshCommandHandler.handle(new RefreshCommandHandler.Command(rawRefreshToken));
        addTokenCookies(response, tokens);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(currentUserQueryHandler.handle(
                new CurrentUserQueryHandler.Query(user.getId())));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(value = "accessToken", required = false) String rawAccessToken,
            @CookieValue(value = "refreshToken", required = false) String rawRefreshToken,
            HttpServletResponse response
    ) {
        clearTokenCookies(response);
        String tokenJti = null;
        long ttlSeconds = 0;
        if (rawAccessToken != null) {
            TokenPort.TokenDetails details = tokenPort.extractTokenDetails(rawAccessToken);
            if (details != null) {
                tokenJti = details.jti();
                ttlSeconds = details.ttlSeconds();
            }
        }
        logoutCommandHandler.handle(new LogoutCommandHandler.Command(tokenJti, ttlSeconds, rawRefreshToken));
        return ResponseEntity.noContent().build();
    }

    private void addTokenCookies(HttpServletResponse response, AuthTokens tokens) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                buildCookie("accessToken", tokens.accessToken(), accessTokenExpiryMs / 1000).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
                buildCookie("refreshToken", tokens.refreshToken(), refreshTokenExpiryMs / 1000).toString());
    }

    private void clearTokenCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken", "", 0).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("refreshToken", "", 0).toString());
    }

    private ResponseCookie buildCookie(String name, String value, long maxAgeSeconds) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/")
                .maxAge(maxAgeSeconds)
                .build();
    }
}
