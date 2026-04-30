package com.payflow.infrastructure.reconciliation;

import com.payflow.infrastructure.persistence.jpa.LedgerReconciliationRepository;
import com.payflow.infrastructure.persistence.jpa.WalletReconciliationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReconciliationService {
    private final LedgerReconciliationRepository ledgerRepo;
    private final WalletReconciliationRepository walletRepo;
    private final ReconciliationAlertService alertService;

    @Scheduled(cron = "0 0 2 * * *")
    public void reconcile()
    {
        log.info("[RECONCILIATION] Starting daily reconciliation");
        checkGlobalBalance();
        checkWalletCache();
        log.info("[RECONCILIATION] Reconciliation completed");

    }

    private void checkGlobalBalance() {
        long delta = ledgerRepo.computeGlobalDelta();
        if(delta!=0)
        {
            log.error("[RECONCILIATION] Global ledger imbalance detected: delta={}", delta);
            alertService.onGlobalImbalance(delta);
        }
    }

    private void checkWalletCache() {
        List<WalletDiscrepancy> discrepancies = walletRepo.findCacheDiscrepancies();
        if(discrepancies.isEmpty()) return;
        discrepancies.forEach(d ->
                log.error("[RECONCILIATION] Wallet cache mismatch: walletId={} cached={} computed={}",
                        d.walletId(), d.cachedBalance(), d.computedBalance())
        );
        alertService.onWalletDiscrepancies(discrepancies);
    }
}
