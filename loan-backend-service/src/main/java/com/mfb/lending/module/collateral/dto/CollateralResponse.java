package com.mfb.lending.module.collateral.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CollateralResponse(
        UUID id,
        String reference,
        UUID customerId,
        String collateralType,
        String description,
        String ownerName,
        String ownerIdentifier,
        String identificationNumber,
        String location,
        BigDecimal marketValue,
        BigDecimal forcedSaleValue,
        BigDecimal eligibleLendingValue,
        String currencyCode,
        BigDecimal lendingRatio,
        LocalDate valuationDate,
        LocalDate valuationExpiresOn,
        boolean valuationExpired,
        String valuerName,
        String valuerReference,
        String verificationStatus,
        String ownershipStatus,
        String documentStatus,
        String legalStatus,
        String insuranceStatus,
        String encumbranceStatus,
        BigDecimal allocatedAmount,
        BigDecimal availableAmount,
        UUID approvedBy,
        Instant createdAt,
        Instant updatedAt
) {
}
