package com.payflow.api.dto.response;



public record AuthTokens (
        String accessToken,
        String refreshToken,
        String email
) { }
