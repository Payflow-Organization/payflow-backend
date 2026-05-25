package com.payflow.infrastructure.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

public class RoutingDataSource extends AbstractRoutingDataSource {
    @Override
    protected Object determineCurrentLookupKey() { // ADR-016: readOnly=true routes to replica, all else to primary
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? "read"
                : "write";
    }
}
