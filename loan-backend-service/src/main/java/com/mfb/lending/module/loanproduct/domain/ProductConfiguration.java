package com.mfb.lending.module.loanproduct.domain;

import java.math.BigDecimal;
import java.util.List;

public record ProductConfiguration(
        String productName,
        String description,
        CustomerSegment customerSegment,
        BigDecimal minimumAmount,
        BigDecimal maximumAmount,
        int minimumTenure,
        int maximumTenure,
        TenureUnit tenureUnit,
        BigDecimal interestRate,
        InterestMethod interestMethod,
        InterestPeriod interestPeriod,
        RepaymentFrequency repaymentFrequency,
        DayCountConvention dayCountConvention,
        int roundingPrecision,
        RoundingMethod roundingMethod,
        int gracePeriodDays,
        EarlyRepaymentRule earlyRepaymentRule,
        BigDecimal earlyRepaymentFeeRate,
        boolean guarantorRequired,
        int minimumGuarantors,
        String guarantorNotes,
        boolean creditBureauRequired,
        Integer minimumBureauScore,
        PenaltyBasis penaltyBasis,
        BigDecimal penaltyRate,
        BigDecimal penaltyAmount,
        int penaltyGraceDays,
        List<ProductFee> fees,
        List<ProductDocumentRequirement> documents,
        List<EligibilityRule> eligibilityRules,
        List<CollateralRule> collateralRules,
        List<ApprovalRule> approvalRules
) {
}
