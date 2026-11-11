package com.mfb.lending.module.credit.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record SmeAssessmentRequest(
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal monthlyRevenue,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal operatingExpenses,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal netOperatingCashFlow,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal existingLiabilities,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal existingDebtService,
        @Min(0) @Max(1200) Integer businessAgeMonths,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal accountTurnover,
        String businessSector,
        Boolean financialStatementsAvailable,
        Boolean financialStatementsAudited
) {
}
