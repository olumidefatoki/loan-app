package com.mfb.lending.module.collateral.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GuarantorResponse(
        UUID id,
        String reference,
        String fullName,
        String phoneNumber,
        String email,
        String addressLine,
        String bvnMasked,
        String ninMasked,
        String bvnVerificationReference,
        String ninVerificationReference,
        String employmentStatus,
        String employerName,
        String jobTitle,
        BigDecimal monthlyIncome,
        BigDecimal monthlyObligations,
        String verificationStatus,
        String acceptanceStatus,
        boolean approvedSecurity,
        boolean consentRecorded,
        String rejectionReason,
        List<StoredDocumentResponse> documents,
        Instant createdAt,
        Instant updatedAt
) {
}
