package com.mfb.lending.module.approval.domain;

public enum ApprovalStatus {
    PENDING,
    INFORMATION_REQUESTED,
    APPROVED,
    REJECTED,
    RETURNED,
    EXPIRED,
    SUPERSEDED;

    public boolean open() {
        return this == PENDING || this == INFORMATION_REQUESTED;
    }
}
