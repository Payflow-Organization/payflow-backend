package com.payflow.api.dto.response;

import com.payflow.domain.model.transaction.TransactionType;

public record SpendingByCategoryResponse(TransactionType transactionType,
                                         long totalCents,
                                         long count) {
}
