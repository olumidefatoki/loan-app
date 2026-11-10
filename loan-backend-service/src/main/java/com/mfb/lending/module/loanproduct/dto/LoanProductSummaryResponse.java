package com.mfb.lending.module.loanproduct.dto;

import java.math.BigDecimal;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Catalogue row for a loan product.")
public record LoanProductSummaryResponse(
        UUID id,
        String productCode,
        String productName,
        String category,
        String status,
        String customerSegment,
        String currencyCode,
        BigDecimal minimumAmount,
        BigDecimal maximumAmount,
        int minimumTenure,
        int maximumTenure,
        String tenureUnit,
        BigDecimal interestRate,
        String interestMethod,
        String repaymentFrequency,
        int versionNumber
) {
}
