package com.mfb.lending.module.integration.corebanking;

public enum FinancialTransactionState {
    INITIATED,
    PENDING,
    SUBMITTED,
    UNKNOWN,
    SUCCESSFUL,
    FAILED,
    REVERSED,
    RECONCILIATION_REQUIRED;

    public boolean terminal() {
        return this == FAILED || this == REVERSED;
    }

    public boolean countsAsPosted() {
        return this == SUCCESSFUL || this == RECONCILIATION_REQUIRED;
    }
}
