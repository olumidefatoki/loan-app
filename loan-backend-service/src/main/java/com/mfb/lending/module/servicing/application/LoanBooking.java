package com.mfb.lending.module.servicing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.module.loancalculation.LoanCalculationEngine.FeeInput;
import com.mfb.lending.module.loanproduct.domain.DayCountConvention;
import com.mfb.lending.module.loanproduct.domain.EarlyRepaymentRule;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.InterestPeriod;
import com.mfb.lending.module.loanproduct.domain.PenaltyBasis;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;

public record LoanBooking(
        UUID applicationId,
        UUID customerId,
        UUID branchId,
        UUID productId,
        UUID productVersionId,
        UUID offerId,
        BigDecimal principal,
        BigDecimal interestRate,
        InterestMethod interestMethod,
        InterestPeriod interestPeriod,
        int tenure,
        TenureUnit tenureUnit,
        RepaymentFrequency frequency,
        DayCountConvention dayCount,
        int scale,
        RoundingMode rounding,
        int gracePeriodDays,
        BigDecimal taxRate,
        List<FeeInput> fees,
        EarlyRepaymentRule earlyRule,
        BigDecimal earlyFeeRate,
        PenaltyBasis penaltyBasis,
        BigDecimal penaltyRate,
        BigDecimal penaltyAmount,
        int penaltyGraceDays,
        boolean disbursementConfirmed,
        String coreReference
) {
}
