package com.mfb.lending.module.collateral.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CollateralRecord(
        UUID id,
        String reference,
        UUID customerId,
        CollateralType type,
        String description,
        String ownerName,
        String ownerIdentifier,
        String identificationNumber,
        String location,
        BigDecimal marketValue,
        BigDecimal forcedSaleValue,
        BigDecimal eligibleLendingValue,
        LocalDate valuationDate,
        LocalDate valuationExpiresOn,
        String valuerName,
        String valuerReference,
        BigDecimal lendingRatio,
        VerificationStatus verificationStatus,
        CheckStatus ownershipStatus,
        CheckStatus documentStatus,
        CheckStatus legalStatus,
        InsuranceCheckStatus insuranceStatus,
        EncumbranceStatus encumbranceStatus,
        UUID approvedBy,
        Instant approvedAt,
        UUID rejectedBy,
        Instant rejectedAt,
        String rejectionReason,
        Instant releasedAt,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
