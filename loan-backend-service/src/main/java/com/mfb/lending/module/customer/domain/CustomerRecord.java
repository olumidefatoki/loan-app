package com.mfb.lending.module.customer.domain;

import java.time.Instant;
import java.util.UUID;

public record CustomerRecord(
        UUID id,
        String reference,
        CustomerKind kind,
        CustomerStatus status,
        CustomerCategory category,
        String displayName,
        String email,
        String phoneNumber,
        UUID userId,
        UUID branchId,
        KycStatus kycStatus,
        RiskClassification riskClassification,
        boolean blacklisted,
        String blacklistReason,
        boolean complianceHold,
        String complianceHoldReason,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
