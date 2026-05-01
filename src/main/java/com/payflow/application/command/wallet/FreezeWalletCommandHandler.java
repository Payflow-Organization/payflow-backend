package com.payflow.application.command.wallet;

import com.payflow.application.service.WalletService;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.model.wallet.WalletNotFoundException;
import com.payflow.domain.repository.WalletRepository;

import java.util.UUID;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class FreezeWalletCommandHandler {

    public record Command(UUID walletId, UUID userId) {}

    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final MeterRegistry meterRegistry;
    private static final String CURRENCY_TAG = "currency";

    @Retryable(
            retryFor = {ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class},
            maxAttemptsExpression = "${payflow.retry.max-attempts:3}",
            backoff = @Backoff(
                    delayExpression = "${payflow.retry.initial-interval-ms:100}",
                    multiplierExpression = "${payflow.retry.multiplier:2.0}",
                    maxDelayExpression = "${payflow.retry.max-interval-ms:1000}"
            )
    )
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void handle(Command command) {
        Timer.Sample timer = Timer.start(meterRegistry);
        Wallet wallet = null;
        try {
            wallet = walletRepository.findByIdAndUserId(command.walletId(), command.userId())
                    .orElseThrow(() -> {
                        meterRegistry.counter("payment.wallet.freeze.failure",
                                CURRENCY_TAG, "unknown",
                                "reason", "not_found"
                        );
                        return new WalletNotFoundException(command.walletId());
                    });

            wallet.freeze();
            meterRegistry.counter("payment.wallet.freeze.success",
                    CURRENCY_TAG, wallet.getCurrency().getCurrencyCode()
            );
            log.warn("Wallet frozen walletId={} userId={}",
                    command.walletId(), command.userId());
            walletService.save(wallet);
        } finally {
            timer.stop(Timer.builder("payment.wallet.freeze.latency")
                    .tag(CURRENCY_TAG, wallet.getCurrency().getCurrencyCode())
                    .register(meterRegistry));
        }
    }
}
