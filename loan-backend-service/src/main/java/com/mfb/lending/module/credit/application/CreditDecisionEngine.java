package com.mfb.lending.module.credit.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.mfb.lending.module.credit.domain.AssessmentFacts;
import com.mfb.lending.module.credit.domain.BureauCheckStatus;
import com.mfb.lending.module.credit.domain.BureauView;
import com.mfb.lending.module.credit.domain.CalculationLine;
import com.mfb.lending.module.credit.domain.CalculationSet;
import com.mfb.lending.module.credit.domain.CreditRule;
import com.mfb.lending.module.credit.domain.DecisionInput;
import com.mfb.lending.module.credit.domain.DecisionOutcome;
import com.mfb.lending.module.credit.domain.DecisionReason;
import com.mfb.lending.module.credit.domain.DecisionResult;
import com.mfb.lending.module.credit.domain.DecisionThresholds;
import com.mfb.lending.module.credit.domain.Finding;
import com.mfb.lending.module.credit.domain.FindingClass;
import com.mfb.lending.module.credit.domain.FindingSeverity;
import com.mfb.lending.module.credit.domain.RepaymentHistory;
import com.mfb.lending.module.credit.domain.RuleType;
import com.mfb.lending.module.credit.domain.ScoreResult;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.KycStatus;
import com.mfb.lending.module.loanapplication.domain.EmploymentStatus;

public final class CreditDecisionEngine {

    private CreditDecisionEngine() {
    }

    public static DecisionResult decide(DecisionInput input) {
        RepaymentHistory effectiveHistory = RepaymentHistory.worse(
                input.facts().repaymentHistory(),
                input.bureau() == null ? null : input.bureau().reportedRepayment());
        AssessmentFacts facts = input.facts().withRepaymentHistory(effectiveHistory);
        boolean collateralRequired = input.collateralRequired();
        CalculationSet calculations = calculations(facts, collateralRequired);
        ScoreResult score = CreditScoreCalculator.score(
                input.modelId(),
                input.modelVersionId(),
                input.policyVersion(),
                input.weights(),
                input.riskCategories(),
                facts,
                input.bureau(),
                calculations);

        List<Finding> findings = new ArrayList<>();
        if (effectiveHistory != input.facts().repaymentHistory() && input.facts().repaymentHistory() != null) {
            findings.add(info(
                    "BUREAU_REPAYMENT",
                    "Bureau repayment history " + effectiveHistory + " is worse than the stated history and was used."));
        }
        missingFields(facts, collateralRequired, input.amount(), findings);
        calculationProblems(calculations, findings);
        for (String metric : score.missingMetrics()) {
            findings.add(data(metric, metric + " is required by the active scoring model."));
        }
        for (CreditRule rule : input.rules()) {
            if (!rule.active() || rule.ruleType() == RuleType.SCORE_THRESHOLD) {
                continue;
            }
            RuleEffect effect = evaluate(rule, input, facts, calculations);
            if (effect == RuleEffect.PASS || effect == RuleEffect.NOT_APPLICABLE) {
                continue;
            }
            Finding finding = findingFor(rule, effect);
            if (effect == RuleEffect.FAIL
                    && finding.waiverAllowed()
                    && input.approvedExceptions().contains(rule.code())) {
                findings.add(info(rule.code(), "Approved policy exception waives " + rule.code() + "."));
                continue;
            }
            findings.add(finding);
        }

        DecisionOutcome outcome = outcome(findings, score, input);
        findings.add(scoreReason(outcome, score, input.thresholds(), input.approvedExceptions().contains("SCORE_THRESHOLD")));
        List<DecisionReason> reasons = findings.stream()
                .filter(finding -> finding != null)
                .map(finding -> new DecisionReason(finding.code(), finding.severity(), finding.message(), finding.blocksApproval()))
                .toList();
        return new DecisionResult(outcome, reasons, score, calculations);
    }

    public static CalculationSet calculations(AssessmentFacts facts, boolean collateralRequired) {
        BigDecimal monthlyDebt = CreditCalculations.monthlyDebt(
                facts.existingLoanObligations(), facts.proposedMonthlyInstalment());
        CalculationLine dti = facts.segment() == CustomerKind.SME
                ? CalculationLine.notApplicable(CreditCalculations.DTI, "monthlyDebtObligations / monthlyGrossIncome")
                : CreditCalculations.dti(monthlyDebt, facts.monthlyGrossIncome());
        BigDecimal debtService = CreditCalculations.debtService(facts.existingDebtService(), facts.proposedMonthlyInstalment());
        CalculationLine dscr = facts.segment() == CustomerKind.INDIVIDUAL
                ? CalculationLine.notApplicable(CreditCalculations.DSCR, "availableOperatingCashFlow / totalDebtService")
                : CreditCalculations.dscr(facts.netOperatingCashFlow(), debtService);
        CalculationLine collateral = collateralRequired
                || facts.eligibleCollateralValue() != null
                || facts.totalSecuredExposure() != null
                ? CreditCalculations.collateralCoverage(facts.eligibleCollateralValue(), facts.totalSecuredExposure())
                : CalculationLine.notApplicable(
                        CreditCalculations.COLLATERAL, "eligibleCollateralValue / totalSecuredExposure");
        CalculationLine disposable = facts.segment() == CustomerKind.SME
                ? CalculationLine.notApplicable(
                        CreditCalculations.DISPOSABLE, "netIncome - monthlyExpenses - monthlyDebtObligations")
                : CreditCalculations.disposableIncome(facts.netIncome(), facts.monthlyExpenses(), monthlyDebt);
        return new CalculationSet(dti, dscr, collateral, disposable);
    }

    private static DecisionOutcome outcome(List<Finding> findings, ScoreResult score, DecisionInput input) {
        if (findings.stream().anyMatch(finding -> finding.classification() == FindingClass.COMPLIANCE && finding.blocksApproval())) {
            return DecisionOutcome.COMPLIANCE_HOLD;
        }
        if (findings.stream().anyMatch(finding -> finding.classification() == FindingClass.HARD_POLICY && finding.blocksApproval())) {
            return DecisionOutcome.AUTO_REJECT;
        }
        if (findings.stream().anyMatch(finding -> finding.classification() == FindingClass.AFFORDABILITY && finding.blocksApproval())) {
            return DecisionOutcome.AUTO_REJECT;
        }
        if (findings.stream().anyMatch(finding -> finding.classification() == FindingClass.DATA && finding.blocksApproval())
                || !score.complete()) {
            return DecisionOutcome.PENDING_DATA;
        }
        BigDecimal value = score.score();
        DecisionThresholds thresholds = input.thresholds();
        boolean referLifted = input.approvedExceptions().contains("SCORE_THRESHOLD")
                && value.compareTo(thresholds.referMinimum()) >= 0;
        if (value.compareTo(thresholds.autoApproveMinimum()) >= 0 || referLifted) {
            return DecisionOutcome.AUTO_APPROVE;
        }
        if (value.compareTo(thresholds.referMinimum()) >= 0) {
            return DecisionOutcome.REFER;
        }
        return DecisionOutcome.AUTO_REJECT;
    }

    private static Finding scoreReason(
            DecisionOutcome outcome,
            ScoreResult score,
            DecisionThresholds thresholds,
            boolean scoreExceptionApproved) {
        if (!score.complete() || score.score() == null) {
            return null;
        }
        if (outcome == DecisionOutcome.COMPLIANCE_HOLD || outcome == DecisionOutcome.PENDING_DATA) {
            return info("SCORE_RECORDED", "Score " + score.score().toPlainString()
                    + " was recorded and did not override the decision.");
        }
        if (outcome == DecisionOutcome.AUTO_APPROVE && scoreExceptionApproved
                && score.score().compareTo(thresholds.autoApproveMinimum()) < 0) {
            return new Finding(
                    "SCORE_THRESHOLD",
                    FindingSeverity.INFO,
                    FindingClass.INFO,
                    "Approved score exception lifts a referred score of " + score.score().toPlainString() + ".",
                    false,
                    false);
        }
        if (outcome == DecisionOutcome.AUTO_APPROVE) {
            return info("SCORE_MEETS_THRESHOLD", "Score " + score.score().toPlainString()
                    + " meets the approval threshold of " + thresholds.autoApproveMinimum().toPlainString() + ".");
        }
        if (outcome == DecisionOutcome.REFER) {
            return info("SCORE_REFER", "Score " + score.score().toPlainString()
                    + " is at or above " + thresholds.referMinimum().toPlainString()
                    + " and below " + thresholds.autoApproveMinimum().toPlainString() + ".");
        }
        if (outcome == DecisionOutcome.AUTO_REJECT
                && score.score().compareTo(thresholds.referMinimum()) < 0) {
            return new Finding(
                    "SCORE_BELOW_REFER",
                    FindingSeverity.POLICY,
                    FindingClass.AFFORDABILITY,
                    "Score " + score.score().toPlainString() + " is below the refer threshold of "
                            + thresholds.referMinimum().toPlainString() + ".",
                    true,
                    false);
        }
        return info("SCORE_RECORDED", "Score " + score.score().toPlainString()
                + " was recorded and did not override the policy decision.");
    }

    private static void missingFields(
            AssessmentFacts facts,
            boolean collateralRequired,
            BigDecimal amount,
            List<Finding> findings) {
        if (amount == null) {
            findings.add(data("AMOUNT", "The requested amount is required."));
        }
        if (facts.segment() == CustomerKind.INDIVIDUAL) {
            require(facts.monthlyGrossIncome(), "MONTHLY_GROSS_INCOME", "Monthly gross income is required.", findings);
            require(facts.netIncome(), "NET_INCOME", "Net income is required.", findings);
            require(facts.monthlyExpenses(), "MONTHLY_EXPENSES", "Monthly expenses are required.", findings);
            require(facts.existingLoanObligations(), "EXISTING_LOAN_OBLIGATIONS", "Existing loan obligations are required.", findings);
            require(facts.proposedMonthlyInstalment(), "PROPOSED_MONTHLY_INSTALMENT", "The proposed monthly instalment is required.", findings);
            require(facts.bankAccountTurnover(), "BANK_ACCOUNT_TURNOVER", "Bank account turnover is required.", findings);
            require(facts.employmentMonths(), "EMPLOYMENT_MONTHS", "Employment stability is required.", findings);
            require(facts.employmentStatus(), "EMPLOYMENT_STATUS", "Employment status is required.", findings);
        } else {
            require(facts.monthlyRevenue(), "MONTHLY_REVENUE", "Monthly revenue is required.", findings);
            require(facts.operatingExpenses(), "OPERATING_EXPENSES", "Operating expenses are required.", findings);
            require(facts.netOperatingCashFlow(), "NET_OPERATING_CASH_FLOW", "Net operating cash flow is required.", findings);
            require(facts.existingLiabilities(), "EXISTING_LIABILITIES", "Existing liabilities are required.", findings);
            require(facts.existingDebtService(), "EXISTING_DEBT_SERVICE", "Existing debt service is required.", findings);
            require(facts.proposedMonthlyInstalment(), "PROPOSED_MONTHLY_INSTALMENT", "The proposed monthly instalment is required.", findings);
            require(facts.businessAgeMonths(), "BUSINESS_AGE", "Business age is required.", findings);
            require(facts.accountTurnover(), "ACCOUNT_TURNOVER", "Account turnover is required.", findings);
            require(facts.businessSector(), "BUSINESS_SECTOR", "Business sector is required.", findings);
            require(facts.financialStatementsAvailable(), "FINANCIAL_STATEMENTS", "Financial statement information is required.", findings);
        }
        require(facts.repaymentHistory(), "REPAYMENT_HISTORY", "Repayment history is required.", findings);
        if (collateralRequired) {
            require(facts.eligibleCollateralValue(), "ELIGIBLE_COLLATERAL_VALUE", "Eligible collateral value is required.", findings);
            require(facts.totalSecuredExposure(), "TOTAL_SECURED_EXPOSURE", "Total secured exposure is required.", findings);
        }
    }

    private static void calculationProblems(CalculationSet calculations, List<Finding> findings) {
        problem(calculations.dti(), "DTI", findings);
        problem(calculations.dscr(), "DSCR", findings);
        problem(calculations.collateralCoverage(), "COLLATERAL_COVERAGE", findings);
        problem(calculations.disposableIncome(), "DISPOSABLE_INCOME", findings);
    }

    private static void problem(CalculationLine line, String code, List<Finding> findings) {
        if (line.unavailableReason() == null
                || "NOT_APPLICABLE".equals(line.unavailableReason())
                || "INPUT_MISSING".equals(line.unavailableReason())) {
            return;
        }
        findings.add(data(code, code + " could not be calculated: " + line.unavailableReason() + "."));
    }

    private static void require(Object value, String code, String message, List<Finding> findings) {
        if (value == null) {
            findings.add(data(code, message));
        }
    }

    private static RuleEffect evaluate(
            CreditRule rule,
            DecisionInput input,
            AssessmentFacts facts,
            CalculationSet calculations) {
        return switch (rule.ruleType()) {
            case KYC_VERIFIED -> input.kycStatus() == KycStatus.VERIFIED ? RuleEffect.PASS : RuleEffect.FAIL;
            case CUSTOMER_ACTIVE -> input.customerStatus() == CustomerStatus.ACTIVE ? RuleEffect.PASS : RuleEffect.FAIL;
            case COMPLIANCE_CLEAR -> input.complianceHold() ? RuleEffect.FAIL : RuleEffect.PASS;
            case NOT_BLACKLISTED -> input.blacklisted() ? RuleEffect.FAIL : RuleEffect.PASS;
            case PRODUCT_ELIGIBLE -> input.productEligible() ? RuleEffect.PASS : RuleEffect.FAIL;
            case LOAN_LIMIT -> loanLimit(input);
            case DTI_MAX -> ceiling(calculations.dti().output(), rule.threshold());
            case DSCR_MIN -> floor(calculations.dscr().output(), rule.threshold());
            case COLLATERAL_COVERAGE_MIN -> collateral(input, calculations, rule.threshold());
            case DISPOSABLE_INCOME_MIN -> floor(calculations.disposableIncome().output(), rule.threshold());
            case EMPLOYMENT_ACCEPTABLE -> employment(facts.employmentStatus());
            case REPAYMENT_DEFAULT -> facts.repaymentHistory() == null
                    ? RuleEffect.NOT_APPLICABLE
                    : facts.repaymentHistory() == RepaymentHistory.DEFAULT ? RuleEffect.FAIL : RuleEffect.PASS;
            case REPAYMENT_DELINQUENT -> facts.repaymentHistory() == null
                    ? RuleEffect.NOT_APPLICABLE
                    : facts.repaymentHistory() == RepaymentHistory.DELINQUENT ? RuleEffect.FAIL : RuleEffect.PASS;
            case BUREAU_REQUIRED -> bureauRequired(input);
            case BUREAU_SCORE_MIN -> bureauScore(input, rule.threshold());
            case EXPOSURE_LIMIT -> exposure(input, rule.threshold());
            case BUSINESS_AGE_MIN -> facts.businessAgeMonths() == null || rule.threshold() == null
                    ? RuleEffect.NOT_APPLICABLE
                    : new BigDecimal(facts.businessAgeMonths()).compareTo(rule.threshold()) < 0
                            ? RuleEffect.FAIL : RuleEffect.PASS;
            case FINANCIAL_STATEMENTS -> facts.financialStatementsAvailable() == null
                    ? RuleEffect.NOT_APPLICABLE
                    : facts.financialStatementsAvailable() ? RuleEffect.PASS : RuleEffect.FAIL;
            case SCORE_THRESHOLD -> RuleEffect.NOT_APPLICABLE;
        };
    }

    private static RuleEffect loanLimit(DecisionInput input) {
        if (input.amount() == null || input.minimumAmount() == null || input.maximumAmount() == null) {
            return RuleEffect.NOT_APPLICABLE;
        }
        if (input.amount().compareTo(input.minimumAmount()) < 0 || input.amount().compareTo(input.maximumAmount()) > 0) {
            return RuleEffect.FAIL;
        }
        return RuleEffect.PASS;
    }

    private static RuleEffect collateral(DecisionInput input, CalculationSet calculations, BigDecimal ruleThreshold) {
        if (!input.collateralRequired() && "NOT_APPLICABLE".equals(calculations.collateralCoverage().unavailableReason())) {
            return RuleEffect.NOT_APPLICABLE;
        }
        BigDecimal coverage = calculations.collateralCoverage().output();
        if (coverage == null) {
            return RuleEffect.NOT_APPLICABLE;
        }
        BigDecimal minimum = ruleThreshold == null ? input.productMinimumCoverage() : ruleThreshold;
        if (input.productMinimumCoverage() != null && (minimum == null || input.productMinimumCoverage().compareTo(minimum) > 0)) {
            minimum = input.productMinimumCoverage();
        }
        if (minimum == null) {
            return RuleEffect.PASS;
        }
        return coverage.compareTo(minimum) < 0 ? RuleEffect.FAIL : RuleEffect.PASS;
    }

    private static RuleEffect bureauRequired(DecisionInput input) {
        if (!input.creditBureauRequired()) {
            return RuleEffect.NOT_APPLICABLE;
        }
        BureauView bureau = input.bureau();
        if (bureau != null && bureau.status() == BureauCheckStatus.COMPLETED) {
            return RuleEffect.PASS;
        }
        return RuleEffect.MISSING;
    }

    private static RuleEffect bureauScore(DecisionInput input, BigDecimal threshold) {
        BureauView bureau = input.bureau();
        if (bureau == null || bureau.score() == null) {
            return RuleEffect.NOT_APPLICABLE;
        }
        BigDecimal minimum = threshold == null ? BigDecimal.ZERO : threshold;
        if (input.productMinimumBureauScore() != null) {
            BigDecimal productMinimum = new BigDecimal(input.productMinimumBureauScore());
            if (productMinimum.compareTo(minimum) > 0) {
                minimum = productMinimum;
            }
        }
        return new BigDecimal(bureau.score()).compareTo(minimum) < 0 ? RuleEffect.FAIL : RuleEffect.PASS;
    }

    private static RuleEffect exposure(DecisionInput input, BigDecimal threshold) {
        BureauView bureau = input.bureau();
        if (bureau == null || bureau.existingExposure() == null || input.amount() == null || threshold == null) {
            return RuleEffect.NOT_APPLICABLE;
        }
        return bureau.existingExposure().add(input.amount()).compareTo(threshold) > 0 ? RuleEffect.FAIL : RuleEffect.PASS;
    }

    private static RuleEffect employment(EmploymentStatus status) {
        if (status == null) {
            return RuleEffect.NOT_APPLICABLE;
        }
        if (status == EmploymentStatus.UNEMPLOYED || status == EmploymentStatus.STUDENT) {
            return RuleEffect.FAIL;
        }
        return RuleEffect.PASS;
    }

    private static RuleEffect ceiling(BigDecimal actual, BigDecimal maximum) {
        if (actual == null || maximum == null) {
            return RuleEffect.NOT_APPLICABLE;
        }
        return actual.compareTo(maximum) > 0 ? RuleEffect.FAIL : RuleEffect.PASS;
    }

    private static RuleEffect floor(BigDecimal actual, BigDecimal minimum) {
        if (actual == null || minimum == null) {
            return RuleEffect.NOT_APPLICABLE;
        }
        return actual.compareTo(minimum) < 0 ? RuleEffect.FAIL : RuleEffect.PASS;
    }

    private static Finding findingFor(CreditRule rule, RuleEffect effect) {
        if (effect == RuleEffect.MISSING) {
            return data(rule.code(), rule.description());
        }
        FindingClass classification = switch (rule.ruleType()) {
            case COMPLIANCE_CLEAR, NOT_BLACKLISTED -> FindingClass.COMPLIANCE;
            case KYC_VERIFIED, CUSTOMER_ACTIVE, PRODUCT_ELIGIBLE, LOAN_LIMIT, REPAYMENT_DEFAULT -> FindingClass.HARD_POLICY;
            default -> FindingClass.AFFORDABILITY;
        };
        FindingSeverity severity = classification == FindingClass.COMPLIANCE
                ? FindingSeverity.COMPLIANCE
                : rule.mandatory() ? FindingSeverity.MANDATORY : FindingSeverity.POLICY;
        return new Finding(rule.code(), severity, classification, rule.description(), true, rule.exceptionAllowed());
    }

    private static Finding data(String code, String message) {
        return new Finding(code, FindingSeverity.DATA, FindingClass.DATA, message, true, false);
    }

    private static Finding info(String code, String message) {
        return new Finding(code, FindingSeverity.INFO, FindingClass.INFO, message, false, false);
    }

    private enum RuleEffect {
        PASS,
        FAIL,
        MISSING,
        NOT_APPLICABLE
    }
}
