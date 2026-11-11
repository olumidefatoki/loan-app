package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;

import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.loanapplication.domain.EmploymentStatus;

public record AssessmentFacts(
        CustomerKind segment,
        BigDecimal monthlyGrossIncome,
        BigDecimal netIncome,
        BigDecimal monthlyExpenses,
        BigDecimal existingLoanObligations,
        BigDecimal proposedMonthlyInstalment,
        BigDecimal bankAccountTurnover,
        Integer employmentMonths,
        EmploymentStatus employmentStatus,
        RepaymentHistory repaymentHistory,
        BigDecimal monthlyRevenue,
        BigDecimal operatingExpenses,
        BigDecimal netOperatingCashFlow,
        BigDecimal existingLiabilities,
        BigDecimal existingDebtService,
        Integer businessAgeMonths,
        BigDecimal accountTurnover,
        String businessSector,
        Boolean financialStatementsAvailable,
        Boolean financialStatementsAudited,
        BigDecimal eligibleCollateralValue,
        BigDecimal totalSecuredExposure
) {

    public AssessmentFacts withCollateral(BigDecimal eligibleCollateralValue, BigDecimal totalSecuredExposure) {
        return new AssessmentFacts(
                segment,
                monthlyGrossIncome,
                netIncome,
                monthlyExpenses,
                existingLoanObligations,
                proposedMonthlyInstalment,
                bankAccountTurnover,
                employmentMonths,
                employmentStatus,
                repaymentHistory,
                monthlyRevenue,
                operatingExpenses,
                netOperatingCashFlow,
                existingLiabilities,
                existingDebtService,
                businessAgeMonths,
                accountTurnover,
                businessSector,
                financialStatementsAvailable,
                financialStatementsAudited,
                eligibleCollateralValue,
                totalSecuredExposure);
    }

    public AssessmentFacts withRepaymentHistory(RepaymentHistory repaymentHistory) {
        return new AssessmentFacts(
                segment,
                monthlyGrossIncome,
                netIncome,
                monthlyExpenses,
                existingLoanObligations,
                proposedMonthlyInstalment,
                bankAccountTurnover,
                employmentMonths,
                employmentStatus,
                repaymentHistory,
                monthlyRevenue,
                operatingExpenses,
                netOperatingCashFlow,
                existingLiabilities,
                existingDebtService,
                businessAgeMonths,
                accountTurnover,
                businessSector,
                financialStatementsAvailable,
                financialStatementsAudited,
                eligibleCollateralValue,
                totalSecuredExposure);
    }
}
