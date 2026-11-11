package com.mfb.lending.module.credit.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.credit.domain.AssessmentFacts;
import com.mfb.lending.module.credit.domain.RepaymentHistory;
import com.mfb.lending.module.credit.dto.CreateCreditAssessmentRequest;
import com.mfb.lending.module.credit.dto.IndividualAssessmentRequest;
import com.mfb.lending.module.credit.dto.SmeAssessmentRequest;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.loanapplication.domain.CollateralRecord;
import com.mfb.lending.module.loanapplication.domain.EmploymentStatus;
import com.mfb.lending.module.loanapplication.domain.FinancialProfile;
import com.mfb.lending.module.loanapplication.domain.IndividualFinancials;
import com.mfb.lending.module.loanapplication.domain.Obligation;
import com.mfb.lending.module.loanapplication.domain.SmeFinancials;
import com.mfb.lending.module.loanproduct.domain.CollateralRule;

public final class CreditFactsResolver {

    private CreditFactsResolver() {
    }

    public static AssessmentFacts resolve(
            CustomerKind segment,
            FinancialProfile profile,
            List<CollateralRecord> collaterals,
            List<CollateralRule> collateralRules,
            BigDecimal applicationAmount,
            CreateCreditAssessmentRequest request,
            LocalDate today) {
        if (request.individual() != null && request.sme() != null) {
            throw ValidationException.field("sme", "Send either individual or sme financials");
        }
        if (segment == CustomerKind.INDIVIDUAL && request.sme() != null) {
            throw ValidationException.field("sme", "An individual application cannot carry SME financials");
        }
        if (segment == CustomerKind.SME && request.individual() != null) {
            throw ValidationException.field("individual", "An SME application cannot carry individual financials");
        }
        if (segment == CustomerKind.INDIVIDUAL) {
            return individual(profile, collaterals, collateralRules, applicationAmount, request, today);
        }
        return sme(profile, collaterals, collateralRules, applicationAmount, request);
    }

    private static AssessmentFacts individual(
            FinancialProfile profile,
            List<CollateralRecord> collaterals,
            List<CollateralRule> collateralRules,
            BigDecimal applicationAmount,
            CreateCreditAssessmentRequest request,
            LocalDate today) {
        IndividualFinancials saved = profile == null ? null : profile.individual();
        IndividualAssessmentRequest supplied = request.individual();
        BigDecimal gross = first(supplied == null ? null : supplied.monthlyGrossIncome(), gross(saved));
        BigDecimal obligations = first(supplied == null ? null : supplied.existingLoanObligations(), obligations(profile));
        return new AssessmentFacts(
                CustomerKind.INDIVIDUAL,
                money(gross, "individual.monthlyGrossIncome"),
                money(supplied == null ? null : supplied.netIncome(), "individual.netIncome"),
                money(first(supplied == null ? null : supplied.monthlyExpenses(), saved == null ? null : saved.monthlyExpenses()),
                        "individual.monthlyExpenses"),
                money(obligations, "individual.existingLoanObligations"),
                money(request.proposedMonthlyInstalment(), "proposedMonthlyInstalment"),
                money(request.bankAccountTurnover(), "bankAccountTurnover"),
                months(supplied == null ? null : supplied.employmentMonths(), saved == null ? null : saved.employmentStartDate(), today),
                employment(supplied == null ? null : supplied.employmentStatus(), saved == null ? null : saved.employmentStatus()),
                RepaymentHistory.parse(request.repaymentHistory(), false),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                collateralValue(request, collaterals, collateralRules),
                securedExposure(request, collateralRules, applicationAmount));
    }

    private static AssessmentFacts sme(
            FinancialProfile profile,
            List<CollateralRecord> collaterals,
            List<CollateralRule> collateralRules,
            BigDecimal applicationAmount,
            CreateCreditAssessmentRequest request) {
        SmeFinancials saved = profile == null ? null : profile.sme();
        SmeAssessmentRequest supplied = request.sme();
        BigDecimal revenue = first(supplied == null ? null : supplied.monthlyRevenue(), saved == null ? null : saved.monthlyTurnover());
        BigDecimal expenses = first(supplied == null ? null : supplied.operatingExpenses(), saved == null ? null : saved.operatingExpenses());
        BigDecimal cashFlow = first(supplied == null ? null : supplied.netOperatingCashFlow(), saved == null ? null : saved.monthlyCashFlow());
        if (cashFlow == null && revenue != null && expenses != null) {
            cashFlow = revenue.subtract(expenses);
        }
        String sector = firstText(supplied == null ? null : supplied.businessSector(), request.businessSector());
        return new AssessmentFacts(
                CustomerKind.SME,
                null,
                null,
                null,
                null,
                money(request.proposedMonthlyInstalment(), "proposedMonthlyInstalment"),
                null,
                null,
                null,
                RepaymentHistory.parse(request.repaymentHistory(), false),
                money(revenue, "sme.monthlyRevenue"),
                money(expenses, "sme.operatingExpenses"),
                money(cashFlow, "sme.netOperatingCashFlow"),
                money(first(supplied == null ? null : supplied.existingLiabilities(), saved == null ? null : saved.businessLiabilities()),
                        "sme.existingLiabilities"),
                money(supplied == null ? null : supplied.existingDebtService(), "sme.existingDebtService"),
                supplied == null ? null : supplied.businessAgeMonths(),
                money(first(supplied == null ? null : supplied.accountTurnover(), saved == null ? null : saved.monthlyTurnover()),
                        "sme.accountTurnover"),
                sector(sector),
                firstBoolean(supplied == null ? null : supplied.financialStatementsAvailable(), request.financialStatementsAvailable()),
                firstBoolean(supplied == null ? null : supplied.financialStatementsAudited(), request.financialStatementsAudited()),
                collateralValue(request, collaterals, collateralRules),
                securedExposure(request, collateralRules, applicationAmount));
    }

    private static BigDecimal gross(IndividualFinancials saved) {
        if (saved == null || (saved.monthlyIncome() == null && saved.otherIncome() == null)) {
            return null;
        }
        BigDecimal primary = saved.monthlyIncome() == null ? BigDecimal.ZERO : saved.monthlyIncome();
        BigDecimal other = saved.otherIncome() == null ? BigDecimal.ZERO : saved.otherIncome();
        return primary.add(other);
    }

    private static BigDecimal obligations(FinancialProfile profile) {
        if (profile == null || profile.obligations() == null || profile.obligations().isEmpty()) {
            return profile == null ? null : BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (Obligation obligation : profile.obligations()) {
            if (obligation.monthlyRepayment() != null) {
                total = total.add(obligation.monthlyRepayment());
            }
        }
        return total;
    }

    private static Integer months(Integer supplied, java.time.LocalDate start, LocalDate today) {
        if (supplied != null) {
            if (supplied < 0 || supplied > 1200) {
                throw ValidationException.field("individual.employmentMonths", "employmentMonths is out of range");
            }
            return supplied;
        }
        if (start == null || today == null) {
            return null;
        }
        long months = ChronoUnit.MONTHS.between(start, today);
        if (months < 0) {
            return 0;
        }
        return months > 1200 ? 1200 : (int) months;
    }

    private static EmploymentStatus employment(String supplied, EmploymentStatus saved) {
        if (supplied != null && !supplied.isBlank()) {
            return EmploymentStatus.parse(supplied, "individual.employmentStatus", true);
        }
        return saved;
    }

    private static BigDecimal collateralValue(
            CreateCreditAssessmentRequest request,
            List<CollateralRecord> collaterals,
            List<CollateralRule> rules) {
        if (request.eligibleCollateralValue() != null) {
            return money(request.eligibleCollateralValue(), "eligibleCollateralValue");
        }
        if (collaterals == null || collaterals.isEmpty()) {
            return null;
        }
        List<String> accepted = rules == null ? List.of() : rules.stream().map(CollateralRule::collateralType).toList();
        BigDecimal total = BigDecimal.ZERO;
        boolean any = false;
        for (CollateralRecord collateral : collaterals) {
            if (!accepted.isEmpty() && accepted.stream().noneMatch(type -> type.equalsIgnoreCase(collateral.collateralType()))) {
                continue;
            }
            total = total.add(collateral.estimatedValue());
            any = true;
        }
        return any ? total.setScale(2, java.math.RoundingMode.HALF_UP) : null;
    }

    private static BigDecimal securedExposure(
            CreateCreditAssessmentRequest request,
            List<CollateralRule> rules,
            BigDecimal applicationAmount) {
        if (request.totalSecuredExposure() != null) {
            return money(request.totalSecuredExposure(), "totalSecuredExposure");
        }
        boolean required = rules != null && rules.stream().anyMatch(CollateralRule::mandatory);
        return required ? applicationAmount : null;
    }

    private static BigDecimal first(BigDecimal preferred, BigDecimal fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static String firstText(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }

    private static Boolean firstBoolean(Boolean preferred, Boolean fallback) {
        return preferred != null ? preferred : fallback;
    }

    private static String sector(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 40) {
            throw ValidationException.field("businessSector", "businessSector is too long");
        }
        return normalized;
    }

    private static BigDecimal money(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        if (value.signum() < 0) {
            throw ValidationException.field(field, field + " cannot be negative");
        }
        if (value.scale() > 2) {
            throw ValidationException.field(field, field + " supports at most 2 decimal places");
        }
        return value.setScale(2, java.math.RoundingMode.UNNECESSARY);
    }
}
