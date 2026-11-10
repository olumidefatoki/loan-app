package com.mfb.lending.module.customer.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record KycResponse(
        UUID customerId,
        String kycStatus,
        boolean complianceHold,
        String complianceHoldReason,
        boolean disbursementAllowed,
        List<String> disbursementBlockedReasons,
        List<Check> checks,
        List<ReviewCase> reviewCases
) {

    public record Check(
            UUID id,
            String checkType,
            String outcome,
            String provider,
            String reference,
            String detail,
            Instant completedAt
    ) {
    }

    public record ReviewCase(
            UUID id,
            String caseType,
            String status,
            boolean mandatoryHold,
            String summary,
            Instant openedAt
    ) {
    }
}
