package com.payflow.infrastructure.web;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class CookieService {

    @Value("${app.jwt.expiration}")
    private long accessTokenExpirationMs;

    @Value("${app.jwt.refresh-expiration}")
    private long refreshTokenExpirationMs;

    public void setTokenCookies(HttpServletResponse response, String accessToken, String refreshToken) {
        response.addHeader(HttpHeaders.SET_COOKIE,
                buildCookie("accessToken", accessToken, (int) (accessTokenExpirationMs / 1000)));
        response.addHeader(HttpHeaders.SET_COOKIE,
                buildCookie("refreshToken", refreshToken, (int) (refreshTokenExpirationMs / 1000)));
    }

    public void removeTokenCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("accessToken", "", 0));
        response.addHeader(HttpHeaders.SET_COOKIE, buildCookie("refreshToken", "", 0));
    }

    private String buildCookie(String name, String value, int maxAge) {
        return ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(true)
                .maxAge(maxAge)
                .path("/")
                .sameSite("Strict")
                .build()
                .toString();
    }
}
