package com.mfb.lending.module.collateral.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ValuationRecord(
        UUID id,
        UUID collateralId,
        ValuationKind kind,
        ValuationStatus status,
        BigDecimal marketValue,
        BigDecimal forcedSaleValue,
        BigDecimal lendingRatio,
        BigDecimal calculatedEligibleValue,
        BigDecimal acceptedEligibleValue,
        LocalDate valuationDate,
        LocalDate expiryDate,
        String valuerName,
        String valuerReference,
        UUID submittedBy,
        UUID decidedBy,
        Instant decidedAt,
        String decisionReason,
        Instant createdAt
) {
}
