package com.payflow.api.controller;

import com.payflow.api.dto.response.BalanceHistoryResponse;
import com.payflow.api.dto.response.MonthlySummaryResponse;
import com.payflow.api.dto.response.SpendingByCategoryResponse;
import com.payflow.application.dto.TransactionView;
import com.payflow.application.port.CsvExportPort;
import com.payflow.application.port.PdfExportPort;
import com.payflow.application.query.AnalyticsQueryHandler;
import com.payflow.application.query.WalletStatementQueryHandler;
import com.payflow.domain.model.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsQueryHandler analyticsQueryHandler;
    private final WalletStatementQueryHandler walletStatementQueryHandler;
    private final CsvExportPort csvExportPort;
    private final PdfExportPort pdfExportPort;
    // GET /analytics/balance-history?walletId=&from=&to=&bucket=1 day
    @GetMapping("/balance-history")
    public List<BalanceHistoryResponse> balanceHistory(
            @RequestParam UUID walletId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1 day") String interval,
            @AuthenticationPrincipal User user
    ) {
        var query = new AnalyticsQueryHandler.BalanceHistoryQuery(
                walletId,
                user.getId(),
                from,
                to,
                interval
        );
        return analyticsQueryHandler.balanceHistory(query);
    }

    // GET /analytics/spending-by-category?walletId=&from=&to=
    @GetMapping("/spending-by-category")
    public List<SpendingByCategoryResponse> spendingByCategory(
            @RequestParam UUID walletId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal User user
    ) {
        var query = new AnalyticsQueryHandler.SpendingByCategoryQuery(
                walletId,
                user.getId(),
                from,
                to
        );
        return analyticsQueryHandler.spendingByCategory(query);
    }

    // GET /analytics/monthly-summary?walletId=&month=2025-04
    @GetMapping("/monthly-summary")
    public MonthlySummaryResponse monthlySummary(
            @RequestParam UUID walletId,
            @RequestParam String month,   // e.g. "2025-04"
            @AuthenticationPrincipal User user
    ) {
        var query = new AnalyticsQueryHandler.MonthlySummaryQuery(
                walletId,
                user.getId(),
                YearMonth.parse(month)
        );
        return analyticsQueryHandler.monthlySummary(query);
    }

    @GetMapping(value = "/{walletId}/export")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> export(
            @PathVariable UUID walletId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam String format,
            @AuthenticationPrincipal User user
    ) throws IOException {
        Instant effectiveFrom = from != null ? from : Instant.EPOCH;
        Instant effectiveTo = to != null ? to : Instant.now();
        ExportFormat exportFormat = ExportFormat.fromString(format);

        List<TransactionView> transactions = walletStatementQueryHandler.handle(
                new WalletStatementQueryHandler.Query(user.getId(), walletId, effectiveFrom, effectiveTo)
        ).toList();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        if (exportFormat == ExportFormat.PDF) {
            pdfExportPort.writePdf(walletId, transactions.stream(), out);
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_PDF)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"statement-%s.pdf\"".formatted(walletId))
                    .body(out.toByteArray());
        } else {
            csvExportPort.writeCsv(transactions.stream(), out);
            return ResponseEntity.ok()
                    .contentType(new MediaType("text", "csv"))
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"statement-%s.csv\"".formatted(walletId))
                    .body(out.toByteArray());
        }
    }
}