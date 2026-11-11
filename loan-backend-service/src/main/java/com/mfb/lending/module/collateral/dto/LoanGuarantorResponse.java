package com.mfb.lending.module.collateral.dto;

import java.time.Instant;
import java.util.UUID;

public record LoanGuarantorResponse(
        UUID id,
        UUID applicationId,
        UUID guarantorId,
        String guarantorReference,
        String fullName,
        String relationship,
        boolean approvedSecurity,
        String verificationStatus,
        String acceptanceStatus,
        boolean consentRecorded,
        Instant linkedAt
) {
}
