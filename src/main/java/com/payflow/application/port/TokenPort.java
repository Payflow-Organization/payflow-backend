package com.payflow.application.port;

import org.springframework.security.core.userdetails.UserDetails;

public interface TokenPort {
    String generateAccessToken(UserDetails userDetails);
    boolean validateToken(String token, UserDetails userDetails);
    String extractUsername(String token);
    String extractBearerToken(String authorizationHeader);
    TokenDetails extractTokenDetails(String bearerToken);
    record TokenDetails(String jti, long ttlSeconds) {}
}