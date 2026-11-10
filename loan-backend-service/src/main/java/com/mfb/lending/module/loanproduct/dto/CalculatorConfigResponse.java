package com.mfb.lending.module.loanproduct.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Commercial inputs a calculator needs for the product version currently offered to customers.")
public record CalculatorConfigResponse(
        UUID productId,
        UUID versionId,
        int versionNumber,
        String productCode,
        String productName,
        String currencyCode,
        BigDecimal minimumAmount,
        BigDecimal maximumAmount,
        int minimumTenure,
        int maximumTenure,
        String tenureUnit,
        BigDecimal interestRate,
        String interestMethod,
        String interestPeriod,
        String repaymentFrequency,
        String dayCountConvention,
        int roundingPrecision,
        String roundingMethod,
        int gracePeriodDays,
        String earlyRepaymentRule,
        BigDecimal earlyRepaymentFeeRate,
        String penaltyBasis,
        BigDecimal penaltyRate,
        BigDecimal penaltyAmount,
        int penaltyGraceDays,
        List<LoanProductVersionResponse.FeeResponse> fees
) {
}
