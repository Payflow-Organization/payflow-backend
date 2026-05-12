package com.payflow.api.dto.response;

public record MonthlySummaryResponse(long totalDepositsCents,
                                     long totalWithdrawalsCents,
                                     long netCents,
                                     long transactionCount) {
}
