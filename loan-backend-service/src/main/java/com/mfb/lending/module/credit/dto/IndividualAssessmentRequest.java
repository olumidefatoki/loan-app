package com.mfb.lending.module.credit.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

public record IndividualAssessmentRequest(
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal monthlyGrossIncome,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal netIncome,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal monthlyExpenses,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal existingLoanObligations,
        @Min(0) @Max(1200) Integer employmentMonths,
        String employmentStatus
) {
}
