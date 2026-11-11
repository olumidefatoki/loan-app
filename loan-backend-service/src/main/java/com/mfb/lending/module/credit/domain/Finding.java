package com.mfb.lending.module.credit.domain;

public record Finding(
        String code,
        FindingSeverity severity,
        FindingClass classification,
        String message,
        boolean blocksApproval,
        boolean waiverAllowed
) {
}
