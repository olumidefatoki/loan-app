package com.mfb.lending.module.collateral.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InsuranceRecord(
        UUID id,
        UUID collateralId,
        String insurerName,
        String policyNumber,
        BigDecimal coverAmount,
        LocalDate startDate,
        LocalDate expiryDate,
        Instant createdAt,
        String createdBy
) {
}
