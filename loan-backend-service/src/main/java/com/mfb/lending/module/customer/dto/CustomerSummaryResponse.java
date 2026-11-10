package com.mfb.lending.module.customer.dto;

import java.util.UUID;

public record CustomerSummaryResponse(
        UUID id,
        String customerReference,
        String customerType,
        String status,
        String category,
        String displayName,
        String email,
        String phoneNumber,
        UUID branchId,
        String kycStatus,
        String riskClassification,
        boolean blacklisted,
        boolean complianceHold
) {
}
