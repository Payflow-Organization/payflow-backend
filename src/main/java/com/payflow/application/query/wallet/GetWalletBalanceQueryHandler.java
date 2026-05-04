package com.payflow.application.query.wallet;

import com.payflow.api.dto.response.BalanceResponse;
import com.payflow.domain.model.wallet.Wallet;
import com.payflow.domain.model.wallet.WalletNotFoundException;
import com.payflow.domain.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GetWalletBalanceQueryHandler {

    public record Query(UUID walletId, UUID requestingUserId) {}

    private final WalletRepository walletRepository;

    @Transactional(readOnly = true)
    public BalanceResponse handle(Query query) {
        Wallet wallet = walletRepository.findByIdAndUserId(query.walletId(), query.requestingUserId())
                .orElseThrow(() -> new WalletNotFoundException(query.walletId()));

        return new BalanceResponse(wallet.getId(), wallet.getCurrentBalance(), wallet.getCurrency());
    }
}
