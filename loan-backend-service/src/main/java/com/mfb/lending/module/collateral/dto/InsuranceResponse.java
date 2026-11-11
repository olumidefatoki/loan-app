package com.mfb.lending.module.collateral.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record InsuranceResponse(
        UUID id,
        String insurerName,
        String policyNumber,
        BigDecimal coverAmount,
        String currencyCode,
        LocalDate startDate,
        LocalDate expiryDate
) {
}
