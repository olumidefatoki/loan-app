package com.mfb.lending.module.credit.domain;

public record DecisionReason(
        String code,
        FindingSeverity severity,
        String message,
        boolean blocksApproval
) {
}
