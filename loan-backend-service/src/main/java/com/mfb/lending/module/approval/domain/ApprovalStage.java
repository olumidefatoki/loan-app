package com.mfb.lending.module.approval.domain;

import java.util.Locale;

import com.mfb.lending.common.exception.ValidationException;

public enum ApprovalStage {
    LOAN_OFFICER,
    CREDIT_ANALYST,
    BRANCH_MANAGER,
    CREDIT_MANAGER,
    SENIOR_CREDIT_MANAGER,
    CREDIT_COMMITTEE;

    public static ApprovalStage parse(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ValidationException.field(field, field + " is required");
        }
        try {
            return ApprovalStage.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field(field, "Unknown approval stage");
        }
    }
}
