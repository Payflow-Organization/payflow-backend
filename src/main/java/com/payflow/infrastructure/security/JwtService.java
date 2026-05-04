package com.payflow.infrastructure.security;

import com.payflow.application.port.TokenPort;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.function.Function;

@Service
public class JwtService extends TokenService implements TokenPort {


    public boolean validateToken(String token, UserDetails userDetails) {
        try {
            final String username = extractUsername(token);
            if (username == null) return false;
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException _) {
            return false;
        }
    }
    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    private boolean isTokenExpired(String token) {
        Date expiration = extractExpiration(token);
        return expiration == null || expiration.before(new Date());
    }



    public String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7);
        }
        return null;
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }
    public String extractJti(String token) {
        return extractClaim(token, Claims::getId);
    }

    private <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        if (claims == null) return null;
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(getSignInKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (ExpiredJwtException e) {
            throw e; // let caller handle expiry
        } catch (JwtException | IllegalArgumentException _) {
            return null;
        }
    }

    @Override
    public TokenDetails extractTokenDetails(String bearerToken) {
        String token = extractBearerToken(bearerToken);
        if (token == null) return null;
        try {
            String jti = extractJti(token);
            Date expiration = extractExpiration(token);
            if (jti == null || expiration == null) return null;
            long ttl = Math.max((expiration.getTime() - System.currentTimeMillis()) / 1000, 0);
            return new TokenDetails(jti, ttl);
        } catch (ExpiredJwtException e) {
            String jti = e.getClaims().getId();
            return new TokenDetails(jti, 0L);
        }
    }
}
