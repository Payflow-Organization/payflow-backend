package com.payflow.infrastructure.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Service
public class TokenService {
    @Value("${app.jwt.secret}")
    private String jwtSecret;
    @Value("${app.jwt.expiration}")
    private long jwtExpiration;

    public String generateAccessToken(UserDetails userDetails) {
        return generateAccessToken(Map.of(), userDetails);
    }
    public String generateAccessToken(
            Map<String, Object> extraClaims,
            UserDetails userDetails) {
        return Jwts
                .builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis()+jwtExpiration))
                .id(UUID.randomUUID().toString())
                .signWith(getSignInKey(), Jwts.SIG.HS256).compact(); // ADR-005: HS256 chosen over RS256 — monolith, no external token consumers
    }


    protected SecretKey getSignInKey() {
        byte[] decodedKey;
        try {
            decodedKey = Base64.getDecoder().decode(jwtSecret);
        } catch (IllegalArgumentException _) {
            decodedKey = Base64.getUrlDecoder().decode(jwtSecret);
        }
        return Keys.hmacShaKeyFor(decodedKey);
    }
}
