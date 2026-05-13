package com.payflow.api.controller;

import com.payflow.api.dto.request.CreateWalletRequest;
import com.payflow.api.dto.response.BalanceResponse;
import com.payflow.api.dto.response.WalletResponse;
import com.payflow.application.command.wallet.CreateWalletCommandHandler;
import com.payflow.application.command.wallet.FreezeWalletCommandHandler;
import com.payflow.application.command.wallet.UnfreezeWalletCommandHandler;
import com.payflow.application.query.wallet.GetWalletBalanceQueryHandler;
import com.payflow.application.query.wallet.WalletQueryHandler;
import com.payflow.domain.model.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("api/v1/wallets")
public class WalletController {

    private final WalletQueryHandler walletQueryHandler;
    private final CreateWalletCommandHandler createWalletCommandHandler;
    private final FreezeWalletCommandHandler freezeWalletCommandHandler;
    private final UnfreezeWalletCommandHandler unfreezeWalletCommandHandler;
    private final GetWalletBalanceQueryHandler getWalletBalanceQueryHandler;

    @GetMapping
    public List<WalletResponse> getMyWallets(@AuthenticationPrincipal User user) {
        return walletQueryHandler.handle(new WalletQueryHandler.ListQuery(user.getId()));
    }

    @GetMapping("/{id}")
    public WalletResponse getWalletById(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return walletQueryHandler.handle(new WalletQueryHandler.GetByIdQuery(id, user.getId()));
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(
            @Valid @RequestBody CreateWalletRequest request,
            @AuthenticationPrincipal User user) {
        WalletResponse response = createWalletCommandHandler.handle(
                new CreateWalletCommandHandler.Command(user.getId(), request.currency()));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{id}/balance")
    public BalanceResponse getBalance(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        return getWalletBalanceQueryHandler.handle(
                new GetWalletBalanceQueryHandler.Query(id, user.getId()));
    }

    @PostMapping("/{id}/freeze")
    public ResponseEntity<Void> freeze(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        freezeWalletCommandHandler.handle(new FreezeWalletCommandHandler.Command(id, user.getId()));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/unfreeze")
    public ResponseEntity<Void> unfreeze(@PathVariable UUID id, @AuthenticationPrincipal User user) {
        unfreezeWalletCommandHandler.handle(new UnfreezeWalletCommandHandler.Command(id, user.getId()));
        return ResponseEntity.noContent().build();
    }

}
