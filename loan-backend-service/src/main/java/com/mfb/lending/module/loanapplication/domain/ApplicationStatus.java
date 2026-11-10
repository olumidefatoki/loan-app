package com.mfb.lending.module.loanapplication.domain;

import java.util.Locale;

import com.mfb.lending.common.exception.ValidationException;

public enum ApplicationStatus {
    DRAFT,
    SUBMITTED,
    KYC_PENDING,
    DOCUMENTS_PENDING,
    UNDER_REVIEW,
    CREDIT_ASSESSMENT,
    REFERRED,
    COMPLIANCE_HOLD,
    PENDING_APPROVAL,
    APPROVED,
    REJECTED,
    OFFER_ISSUED,
    OFFER_ACCEPTED,
    PENDING_DISBURSEMENT,
    DISBURSED,
    WITHDRAWN,
    CANCELLED,
    EXPIRED;

    public static ApplicationStatus parse(String value, String field) {
        if (value == null || value.isBlank()) {
            throw ValidationException.field(field, field + " is required");
        }
        try {
            return ApplicationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field(field, "Unknown application status");
        }
    }
}
