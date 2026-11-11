package com.mfb.lending.module.credit.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

public record CreateCreditAssessmentRequest(
        @NotNull UUID applicationId,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal proposedMonthlyInstalment,
        @Valid IndividualAssessmentRequest individual,
        @Valid SmeAssessmentRequest sme,
        String repaymentHistory,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal bankAccountTurnover,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal eligibleCollateralValue,
        @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal totalSecuredExposure,
        String businessSector,
        Boolean financialStatementsAvailable,
        Boolean financialStatementsAudited
) {
}
