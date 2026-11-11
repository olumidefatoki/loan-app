package com.mfb.lending.module.servicing.domain;

public enum RepaymentStatus {
    RECEIVED,
    AWAITING_CONFIRMATION,
    FAILED,
    POSTED,
    REVERSAL_PENDING,
    REVERSED;

    public boolean finished() {
        return this == FAILED || this == POSTED || this == REVERSED;
    }
}
