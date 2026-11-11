package com.mfb.lending.module.collateral.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record GuarantorRecord(
        UUID id,
        String reference,
        String fullName,
        String phoneNumber,
        String email,
        String addressLine,
        String bvnLast4,
        String ninLast4,
        String bvnVerificationReference,
        String ninVerificationReference,
        GuarantorEmployment employmentStatus,
        String employerName,
        String jobTitle,
        BigDecimal monthlyIncome,
        BigDecimal monthlyObligations,
        GuarantorVerificationStatus verificationStatus,
        GuaranteeAcceptanceStatus acceptanceStatus,
        boolean approvedSecurity,
        boolean consentRecorded,
        UUID verifiedBy,
        Instant verifiedAt,
        UUID acceptedBy,
        Instant acceptedAt,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
