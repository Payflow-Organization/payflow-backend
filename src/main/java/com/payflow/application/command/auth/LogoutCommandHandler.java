package com.payflow.application.command.auth;

import com.payflow.application.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class LogoutCommandHandler {

    // Week 1: token string
    // Week 3: tokenJti
    public record Command(String tokenJti, long remainingTtlSeconds, String rawRefreshToken) {}
    private final RefreshTokenService refreshTokenService;
    private final StringRedisTemplate redisTemplate;
    // Week 1: no-op, client discards token
    // Week 3: redisTemplate.opsForValue().set("denylist:" + command.tokenJti(), ...)
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void handle(Command command) {
        long remainingTtlSeconds = command.remainingTtlSeconds();
        if (remainingTtlSeconds > 0) {
            redisTemplate.opsForValue().set(
                    "denylist:" + command.tokenJti(),
                    "1",
                    Duration.ofSeconds(remainingTtlSeconds)
            );
        }
        refreshTokenService.revoke(
                command.rawRefreshToken()
        );

        log.info("User logged out jti={} ttlSeconds={}", command.tokenJti(), remainingTtlSeconds);

    }
}
