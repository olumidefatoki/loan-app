package com.mfb.lending.module.credit;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.module.credit.application.CreditCalculations;
import com.mfb.lending.module.credit.application.CreditDecisionEngine;
import com.mfb.lending.module.credit.application.CreditScoreCalculator;
import com.mfb.lending.module.credit.domain.AssessmentFacts;
import com.mfb.lending.module.credit.domain.BureauCheckStatus;
import com.mfb.lending.module.credit.domain.BureauView;
import com.mfb.lending.module.credit.domain.CreditRule;
import com.mfb.lending.module.credit.domain.DecisionInput;
import com.mfb.lending.module.credit.domain.DecisionOutcome;
import com.mfb.lending.module.credit.domain.DecisionResult;
import com.mfb.lending.module.credit.domain.DecisionThresholds;
import com.mfb.lending.module.credit.domain.RepaymentHistory;
import com.mfb.lending.module.credit.domain.RiskBand;
import com.mfb.lending.module.credit.domain.RuleType;
import com.mfb.lending.module.credit.domain.ScoreDirection;
import com.mfb.lending.module.credit.domain.ScoringComponentSpec;
import com.mfb.lending.module.credit.domain.ScoringWeights;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.KycStatus;
import com.mfb.lending.module.loanapplication.domain.EmploymentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CreditEngineTest {

    @Test
    void debtToIncomeUsesAnIndependentExpectedResult() {
        assertThat(CreditCalculations.dti(new BigDecimal("50000.00"), new BigDecimal("200000.00")).output())
                .isEqualByComparingTo("0.250000");
        assertThat(CreditCalculations.dti(new BigDecimal("1.00"), new BigDecimal("3.00")).output())
                .isEqualByComparingTo("0.333333");
        assertThat(CreditCalculations.dti(new BigDecimal("0.00"), new BigDecimal("100.00")).output())
                .isEqualByComparingTo("0.000000");
        assertThat(CreditCalculations.dti(null, new BigDecimal("100.00")).unavailableReason()).isEqualTo("INPUT_MISSING");
        assertThat(CreditCalculations.dti(new BigDecimal("10.00"), null).unavailableReason()).isEqualTo("INPUT_MISSING");
        assertThat(CreditCalculations.dti(new BigDecimal("10.00"), BigDecimal.ZERO).unavailableReason())
                .isEqualTo("DIVISOR_NOT_POSITIVE");
        assertThat(CreditCalculations.dti(new BigDecimal("-1.00"), new BigDecimal("10.00")).unavailableReason())
                .isEqualTo("NEGATIVE_INPUT");
        assertThat(CreditCalculations.dti(new BigDecimal("50000.00"), new BigDecimal("200000.00")).formula())
                .isEqualTo("DTI_V1");
        assertThat(CreditCalculations.dti(new BigDecimal("50000.00"), new BigDecimal("200000.00")).inputs())
                .containsEntry("monthlyDebtObligations", new BigDecimal("50000.00"))
                .containsEntry("monthlyGrossIncome", new BigDecimal("200000.00"));
    }

    @Test
    void debtServiceCoverageUsesAnIndependentExpectedResult() {
        assertThat(CreditCalculations.dscr(new BigDecimal("150000.00"), new BigDecimal("100000.00")).output())
                .isEqualByComparingTo("1.500000");
        assertThat(CreditCalculations.dscr(new BigDecimal("100000.00"), new BigDecimal("300000.00")).output())
                .isEqualByComparingTo("0.333333");
        assertThat(CreditCalculations.dscr(new BigDecimal("250000.00"), new BigDecimal("125000.00")).output())
                .isEqualByComparingTo("2.000000");
        assertThat(CreditCalculations.dscr(BigDecimal.ZERO, new BigDecimal("100.00")).output())
                .isEqualByComparingTo("0.000000");
        assertThat(CreditCalculations.dscr(new BigDecimal("100.00"), BigDecimal.ZERO).unavailableReason())
                .isEqualTo("DIVISOR_NOT_POSITIVE");
        assertThat(CreditCalculations.dscr(null, new BigDecimal("100.00")).unavailableReason()).isEqualTo("INPUT_MISSING");
    }

    @Test
    void collateralCoverageAndDisposableIncomeUseIndependentExpectedResults() {
        assertThat(CreditCalculations.collateralCoverage(new BigDecimal("150000.00"), new BigDecimal("100000.00")).output())
                .isEqualByComparingTo("1.500000");
        assertThat(CreditCalculations.collateralCoverage(new BigDecimal("80000.00"), new BigDecimal("100000.00")).output())
                .isEqualByComparingTo("0.800000");
        assertThat(CreditCalculations.collateralCoverage(new BigDecimal("100000.00"), new BigDecimal("100000.00")).output())
                .isEqualByComparingTo("1.000000");
        assertThat(CreditCalculations.collateralCoverage(new BigDecimal("1.00"), new BigDecimal("3.00")).output())
                .isEqualByComparingTo("0.333333");
        assertThat(CreditCalculations.disposableIncome(
                new BigDecimal("180000.00"), new BigDecimal("40000.00"), new BigDecimal("50000.00")).output())
                .isEqualByComparingTo("90000.00");
        assertThat(CreditCalculations.disposableIncome(
                new BigDecimal("100.00"), new BigDecimal("80.00"), new BigDecimal("30.00")).output())
                .isEqualByComparingTo("-10.00");
        assertThat(CreditCalculations.disposableIncome(null, new BigDecimal("10.00"), new BigDecimal("10.00")).unavailableReason())
                .isEqualTo("INPUT_MISSING");
    }

    @Test
    void individualScoreMatchesTheHandCalculatedCard() {
        DecisionResult result = CreditDecisionEngine.decide(individual(perfectFacts(), goodBureau(), false, false, Set.of()));
        assertThat(result.score().score()).isEqualByComparingTo("100.00");
        assertThat(result.score().riskCategory()).isEqualTo("LOW");
        assertThat(result.outcome()).isEqualTo(DecisionOutcome.AUTO_APPROVE);
        assertThat(result.calculations().dti().output()).isEqualByComparingTo("0.100000");
        assertThat(result.calculations().dscr().unavailableReason()).isEqualTo("NOT_APPLICABLE");

        DecisionResult referredBand = CreditDecisionEngine.decide(input(
                perfectFacts().withRepaymentHistory(RepaymentHistory.MINOR_ARREARS),
                CustomerKind.INDIVIDUAL,
                repaymentOnly(),
                individualRules(),
                goodBureau(),
                false,
                false,
                false,
                new BigDecimal("200000.00"),
                Set.of(),
                version("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")));
        assertThat(referredBand.score().score()).isEqualByComparingTo("60.00");
        assertThat(referredBand.outcome()).isEqualTo(DecisionOutcome.REFER);
    }

    @Test
    void stretchedDtiScores80Point63AndStillRejectsUntilTheExceptionIsApproved() {
        AssessmentFacts stretched = facts(
                CustomerKind.INDIVIDUAL,
                "200000.00", "180000.00", "30000.00", "70000.00", "20000.00", "200000.00",
                36, EmploymentStatus.EMPLOYED, RepaymentHistory.CLEAN);
        DecisionResult rejected = CreditDecisionEngine.decide(individual(
                stretched, goodBureau(), false, false, Set.of()));
        assertThat(rejected.calculations().dti().output()).isEqualByComparingTo("0.450000");
        assertThat(rejected.calculations().disposableIncome().output()).isEqualByComparingTo("60000.00");
        assertThat(rejected.score().score()).isEqualByComparingTo("80.63");
        assertThat(rejected.outcome()).isEqualTo(DecisionOutcome.AUTO_REJECT);
        assertThat(rejected.reasons()).anyMatch(reason -> reason.code().equals("IND_DTI_MAX") && reason.blocksApproval());

        DecisionResult waived = CreditDecisionEngine.decide(individual(
                stretched, goodBureau(), false, false, Set.of("IND_DTI_MAX")));
        assertThat(waived.outcome()).isEqualTo(DecisionOutcome.AUTO_APPROVE);
        assertThat(waived.score().modelVersionId()).isEqualTo(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    }

    @Test
    void aHighScoreCannotOverrideComplianceKycOrCollateral() {
        BureauView bureau = goodBureau();
        DecisionResult compliance = CreditDecisionEngine.decide(individual(perfectFacts(), bureau, true, false, Set.of("COMPLIANCE_CLEAR")));
        assertThat(compliance.score().score()).isEqualByComparingTo("100.00");
        assertThat(compliance.outcome()).isEqualTo(DecisionOutcome.COMPLIANCE_HOLD);

        DecisionInput kyc = base(perfectFacts(), CustomerKind.INDIVIDUAL, individualWeights(), individualRules(), bureau, false, false, false,
                new BigDecimal("200000.00"), Set.of(), version("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        DecisionResult failedKyc = CreditDecisionEngine.decide(new DecisionInput(
                kyc.customerStatus(), KycStatus.FAILED, kyc.blacklisted(), kyc.complianceHold(), kyc.productEligible(),
                kyc.amount(), kyc.minimumAmount(), kyc.maximumAmount(), kyc.collateralRequired(), kyc.productMinimumCoverage(),
                kyc.creditBureauRequired(), kyc.productMinimumBureauScore(), kyc.bureau(), kyc.facts(), kyc.rules(),
                kyc.approvedExceptions(), kyc.weights(), kyc.riskCategories(), kyc.thresholds(), kyc.modelId(),
                kyc.modelVersionId(), kyc.policyVersion()));
        assertThat(failedKyc.score().score()).isEqualByComparingTo("100.00");
        assertThat(failedKyc.outcome()).isEqualTo(DecisionOutcome.AUTO_REJECT);

        AssessmentFacts secured = facts(
                CustomerKind.INDIVIDUAL,
                "200000.00", "180000.00", "40000.00", "10000.00", "10000.00", "200000.00",
                36, EmploymentStatus.EMPLOYED, RepaymentHistory.CLEAN)
                .withCollateral(new BigDecimal("80000.00"), new BigDecimal("100000.00"));
        DecisionResult shortfall = CreditDecisionEngine.decide(individual(secured, bureau, false, true, Set.of()));
        assertThat(shortfall.calculations().collateralCoverage().output()).isEqualByComparingTo("0.800000");
        assertThat(shortfall.score().score()).isEqualByComparingTo("100.00");
        assertThat(shortfall.outcome()).isEqualTo(DecisionOutcome.AUTO_REJECT);
    }

    @Test
    void missingFinancialsAndBureauFailureStayPending() {
        AssessmentFacts missingNet = facts(
                CustomerKind.INDIVIDUAL,
                "200000.00", null, "40000.00", "10000.00", "10000.00", "200000.00",
                36, EmploymentStatus.EMPLOYED, RepaymentHistory.CLEAN);
        DecisionResult pending = CreditDecisionEngine.decide(individual(
                missingNet, goodBureau(), false, false, Set.of()));
        assertThat(pending.outcome()).isEqualTo(DecisionOutcome.PENDING_DATA);
        assertThat(pending.calculations().dti().output()).isEqualByComparingTo("0.100000");
        assertThat(pending.calculations().disposableIncome().unavailableReason()).isEqualTo("INPUT_MISSING");
        assertThat(pending.score().complete()).isFalse();

        DecisionResult bureauFailed = CreditDecisionEngine.decide(individual(
                perfectFacts(),
                new BureauView(BureauCheckStatus.FAILED, null, null, null),
                false,
                false,
                Set.of()));
        DecisionInput required = individual(perfectFacts(), new BureauView(BureauCheckStatus.FAILED, null, null, null), false, false, Set.of());
        DecisionResult requiredBureau = CreditDecisionEngine.decide(new DecisionInput(
                required.customerStatus(), required.kycStatus(), required.blacklisted(), required.complianceHold(),
                required.productEligible(), required.amount(), required.minimumAmount(), required.maximumAmount(),
                required.collateralRequired(), required.productMinimumCoverage(), true, 500, required.bureau(),
                required.facts(), required.rules(), required.approvedExceptions(), required.weights(),
                required.riskCategories(), required.thresholds(), required.modelId(), required.modelVersionId(),
                required.policyVersion()));
        assertThat(bureauFailed.outcome()).isEqualTo(DecisionOutcome.PENDING_DATA);
        assertThat(requiredBureau.outcome()).isEqualTo(DecisionOutcome.PENDING_DATA);
        assertThat(requiredBureau.reasons()).anyMatch(reason -> reason.code().equals("BUREAU_REQUIRED") && reason.blocksApproval());
    }

    @Test
    void scoreVersionIsRetainedAndALowerCardChangesTheResult() {
        UUID first = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID second = UUID.fromString("22222222-2222-4222-8222-222222222222");
        AssessmentFacts facts = perfectFacts();
        DecisionResult versionOne = CreditDecisionEngine.decide(input(
                facts, CustomerKind.INDIVIDUAL, dtiOnly("0.60"), individualRules(),
                goodBureau(), false, false, false,
                new BigDecimal("200000.00"), Set.of(), first));
        ScoringWeights harder = new ScoringWeights(List.of(
                component("DTI", "1.00", "DTI", ScoreDirection.LOWER_IS_BETTER, "0.05", "0.10")), Map.of());
        DecisionResult versionTwo = CreditDecisionEngine.decide(input(
                facts, CustomerKind.INDIVIDUAL, harder, individualRules(),
                goodBureau(), false, false, false,
                new BigDecimal("200000.00"), Set.of(), second));
        assertThat(versionOne.score().score()).isEqualByComparingTo("100.00");
        assertThat(versionOne.score().modelVersionId()).isEqualTo(first);
        assertThat(versionOne.score().policyVersion()).isEqualTo("POL-2026.1");
        assertThat(versionTwo.score().score()).isEqualByComparingTo("0.00");
        assertThat(versionTwo.score().modelVersionId()).isEqualTo(second);
        assertThat(versionOne.score().modelVersionId()).isNotEqualTo(versionTwo.score().modelVersionId());
    }

    @Test
    void aLowScoreRejectsAndAReferredScoreNeedsAnApprovedException() {
        AssessmentFacts average = facts(
                CustomerKind.INDIVIDUAL,
                "100000.00", "80000.00", "10000.00", "34000.00", "10000.00", "100000.00",
                36, EmploymentStatus.EMPLOYED, RepaymentHistory.CLEAN);
        List<CreditRule> relaxedDti = individualRules().stream()
                .map(rule -> rule.ruleType() == RuleType.DTI_MAX
                        ? rule(rule.code(), RuleType.DTI_MAX, "0.900000", true)
                        : rule)
                .toList();
        DecisionResult low = CreditDecisionEngine.decide(input(
                average, CustomerKind.INDIVIDUAL, dtiOnly("0.60"), relaxedDti,
                goodBureau(), false, false, false,
                new BigDecimal("100000.00"), Set.of(), UUID.randomUUID()));
        assertThat(low.calculations().dti().output()).isEqualByComparingTo("0.440000");
        assertThat(low.score().score()).isEqualByComparingTo("40.00");
        assertThat(low.outcome()).isEqualTo(DecisionOutcome.AUTO_REJECT);

        DecisionResult referred = CreditDecisionEngine.decide(input(
                perfectFacts().withRepaymentHistory(RepaymentHistory.MINOR_ARREARS),
                CustomerKind.INDIVIDUAL, repaymentOnly(), individualRules(),
                goodBureau(), false, false, false,
                new BigDecimal("200000.00"), Set.of(), UUID.randomUUID()));
        assertThat(referred.outcome()).isEqualTo(DecisionOutcome.REFER);
        DecisionResult lifted = CreditDecisionEngine.decide(input(
                perfectFacts().withRepaymentHistory(RepaymentHistory.MINOR_ARREARS),
                CustomerKind.INDIVIDUAL, repaymentOnly(), individualRules(),
                goodBureau(), false, false, false,
                new BigDecimal("200000.00"), Set.of("SCORE_THRESHOLD"), UUID.randomUUID()));
        assertThat(lifted.outcome()).isEqualTo(DecisionOutcome.AUTO_APPROVE);
        DecisionResult stillLow = CreditDecisionEngine.decide(input(
                average, CustomerKind.INDIVIDUAL, dtiOnly("0.60"), relaxedDti,
                goodBureau(), false, false, false,
                new BigDecimal("100000.00"), Set.of("SCORE_THRESHOLD"), UUID.randomUUID()));
        assertThat(stillLow.outcome()).isEqualTo(DecisionOutcome.AUTO_REJECT);
    }

    @Test
    void smeCoverageAndScoreUseTheSmeCard() {
        AssessmentFacts healthy = sme("500000.00", "300000.00", "200000.00", "100000.00", "50000.00", "50000.00", 60, "500000.00");
        DecisionResult approved = CreditDecisionEngine.decide(smeDecision(healthy, Set.of()));
        assertThat(approved.calculations().dscr().output()).isEqualByComparingTo("2.000000");
        assertThat(approved.calculations().dti().unavailableReason()).isEqualTo("NOT_APPLICABLE");
        assertThat(approved.score().score()).isEqualByComparingTo("98.00");
        assertThat(approved.outcome()).isEqualTo(DecisionOutcome.AUTO_APPROVE);

        AssessmentFacts thin = sme("500000.00", "300000.00", "100000.00", "100000.00", "50000.00", "50000.00", 60, "500000.00");
        DecisionResult rejected = CreditDecisionEngine.decide(smeDecision(thin, Set.of()));
        assertThat(rejected.calculations().dscr().output()).isEqualByComparingTo("1.000000");
        assertThat(rejected.outcome()).isEqualTo(DecisionOutcome.AUTO_REJECT);
        assertThat(CreditScoreCalculator.componentScore(
                new ScoringComponentSpec("DSCR", new BigDecimal("1.00"), "DSCR", ScoreDirection.HIGHER_IS_BETTER,
                        new BigDecimal("2.00"), new BigDecimal("1.00")),
                new BigDecimal("1.50"),
                null,
                Map.of())).isEqualByComparingTo("50.00");
    }

    private static BureauView goodBureau() {
        return new BureauView(BureauCheckStatus.COMPLETED, 800, BigDecimal.ZERO, null);
    }

    private static DecisionInput individual(
            AssessmentFacts facts,
            BureauView bureau,
            boolean complianceHold,
            boolean collateralRequired,
            Set<String> exceptions) {
        return base(facts, CustomerKind.INDIVIDUAL, individualWeights(), individualRules(), bureau, complianceHold,
                false, collateralRequired, new BigDecimal("200000.00"), exceptions,
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    }

    private static DecisionInput smeDecision(AssessmentFacts facts, Set<String> exceptions) {
        return base(facts, CustomerKind.SME, smeWeights(), smeRules(), goodBureau(),
                false, false, false, new BigDecimal("200000.00"), exceptions, UUID.randomUUID());
    }

    private static DecisionInput input(
            AssessmentFacts facts,
            CustomerKind segment,
            ScoringWeights weights,
            List<CreditRule> rules,
            BureauView bureau,
            boolean complianceHold,
            boolean bureauRequired,
            boolean collateralRequired,
            BigDecimal amount,
            Set<String> exceptions,
            UUID versionId) {
        return base(facts, segment, weights, rules, bureau, complianceHold, bureauRequired, collateralRequired, amount, exceptions, versionId);
    }

    private static DecisionInput base(
            AssessmentFacts facts,
            CustomerKind segment,
            ScoringWeights weights,
            List<CreditRule> rules,
            BureauView bureau,
            boolean complianceHold,
            boolean bureauRequired,
            boolean collateralRequired,
            BigDecimal amount,
            Set<String> exceptions,
            UUID versionId) {
        return new DecisionInput(
                CustomerStatus.ACTIVE,
                KycStatus.VERIFIED,
                false,
                complianceHold,
                true,
                amount,
                new BigDecimal("1000.00"),
                new BigDecimal("5000000.00"),
                collateralRequired,
                collateralRequired ? BigDecimal.ONE : null,
                bureauRequired,
                bureauRequired ? 500 : null,
                bureau,
                facts,
                rules,
                exceptions,
                weights,
                riskBands(),
                new DecisionThresholds(new BigDecimal("75"), new BigDecimal("55")),
                UUID.randomUUID(),
                versionId,
                "POL-2026.1");
    }

    private static AssessmentFacts perfectFacts() {
        return facts(CustomerKind.INDIVIDUAL, "200000.00", "180000.00", "40000.00", "10000.00", "10000.00",
                "200000.00", 36, EmploymentStatus.EMPLOYED, RepaymentHistory.CLEAN);
    }

    private static AssessmentFacts facts(
            CustomerKind segment,
            String gross,
            String net,
            String expenses,
            String obligations,
            String proposed,
            String turnover,
            int months,
            EmploymentStatus employment,
            RepaymentHistory history) {
        return new AssessmentFacts(
                segment, money(gross), money(net), money(expenses), money(obligations), money(proposed), money(turnover),
                months, employment, history, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static AssessmentFacts sme(
            String revenue,
            String expenses,
            String cashFlow,
            String liabilities,
            String debtService,
            String proposed,
            int age,
            String turnover) {
        return new AssessmentFacts(
                CustomerKind.SME, null, null, null, null, money(proposed), null, null, null, RepaymentHistory.CLEAN,
                money(revenue), money(expenses), money(cashFlow), money(liabilities), money(debtService), age,
                money(turnover), "TRADE", true, true, null, null);
    }

    private static ScoringWeights individualWeights() {
        return new ScoringWeights(List.of(
                component("DTI", "0.25", "DTI", ScoreDirection.LOWER_IS_BETTER, "0.20", "0.60"),
                component("DISPOSABLE_INCOME", "0.15", "DISPOSABLE_INCOME_RATIO", ScoreDirection.HIGHER_IS_BETTER, "0.40", "0.00"),
                component("EMPLOYMENT_STABILITY", "0.15", "EMPLOYMENT_MONTHS", ScoreDirection.HIGHER_IS_BETTER, "36", "0"),
                component("BANK_TURNOVER", "0.10", "TURNOVER_TO_INCOME", ScoreDirection.HIGHER_IS_BETTER, "1.00", "0.00"),
                component("REPAYMENT_HISTORY", "0.20", "REPAYMENT_HISTORY", ScoreDirection.CATEGORICAL, null, null),
                component("BUREAU", "0.15", "BUREAU_SCORE", ScoreDirection.HIGHER_IS_BETTER, "800", "300")),
                Map.of("REPAYMENT_HISTORY", Map.of(
                        "CLEAN", new BigDecimal("100"),
                        "MINOR_ARREARS", new BigDecimal("60"),
                        "DELINQUENT", new BigDecimal("20"),
                        "DEFAULT", BigDecimal.ZERO)));
    }

    private static ScoringWeights smeWeights() {
        return new ScoringWeights(List.of(
                component("DSCR", "0.30", "DSCR", ScoreDirection.HIGHER_IS_BETTER, "2.00", "1.00"),
                component("CASH_FLOW", "0.15", "CASH_FLOW_MARGIN", ScoreDirection.HIGHER_IS_BETTER, "0.25", "0.00"),
                component("BUSINESS_AGE", "0.10", "BUSINESS_AGE_MONTHS", ScoreDirection.HIGHER_IS_BETTER, "60", "0"),
                component("ACCOUNT_TURNOVER", "0.10", "ACCOUNT_TURNOVER_TO_REVENUE", ScoreDirection.HIGHER_IS_BETTER, "1.00", "0.00"),
                component("SECTOR", "0.10", "SECTOR", ScoreDirection.CATEGORICAL, null, null),
                component("BUREAU_EXPOSURE", "0.10", "BUREAU_EXPOSURE_RATIO", ScoreDirection.LOWER_IS_BETTER, "0.00", "1.00"),
                component("REPAYMENT_HISTORY", "0.15", "REPAYMENT_HISTORY", ScoreDirection.CATEGORICAL, null, null)),
                Map.of(
                        "REPAYMENT_HISTORY", Map.of("CLEAN", new BigDecimal("100"), "MINOR_ARREARS", new BigDecimal("60")),
                        "SECTOR", Map.of("TRADE", new BigDecimal("80"), "UNKNOWN", new BigDecimal("40"))));
    }

    private static ScoringWeights repaymentOnly() {
        return new ScoringWeights(List.of(
                component("REPAYMENT_HISTORY", "1.00", "REPAYMENT_HISTORY", ScoreDirection.CATEGORICAL, null, null)),
                Map.of("REPAYMENT_HISTORY", Map.of("CLEAN", new BigDecimal("100"), "MINOR_ARREARS", new BigDecimal("60"))));
    }

    private static ScoringWeights dtiOnly(String worst) {
        return new ScoringWeights(List.of(
                component("DTI", "1.00", "DTI", ScoreDirection.LOWER_IS_BETTER, "0.20", worst)), Map.of());
    }

    private static ScoringComponentSpec component(
            String code, String weight, String metric, ScoreDirection direction, String best, String worst) {
        return new ScoringComponentSpec(code, new BigDecimal(weight), metric, direction,
                best == null ? null : new BigDecimal(best), worst == null ? null : new BigDecimal(worst));
    }

    private static List<RiskBand> riskBands() {
        return List.of(
                new RiskBand("LOW", new BigDecimal("80")),
                new RiskBand("MEDIUM", new BigDecimal("65")),
                new RiskBand("HIGH", new BigDecimal("50")),
                new RiskBand("VERY_HIGH", BigDecimal.ZERO));
    }

    private static List<CreditRule> individualRules() {
        return List.of(
                rule("KYC_VERIFIED", RuleType.KYC_VERIFIED, null, false),
                rule("CUSTOMER_ACTIVE", RuleType.CUSTOMER_ACTIVE, null, false),
                rule("COMPLIANCE_CLEAR", RuleType.COMPLIANCE_CLEAR, null, false),
                rule("NOT_BLACKLISTED", RuleType.NOT_BLACKLISTED, null, false),
                rule("PRODUCT_ELIGIBLE", RuleType.PRODUCT_ELIGIBLE, null, false),
                rule("LOAN_LIMIT", RuleType.LOAN_LIMIT, null, false),
                rule("IND_DTI_MAX", RuleType.DTI_MAX, "0.400000", true),
                rule("IND_DISPOSABLE_MIN", RuleType.DISPOSABLE_INCOME_MIN, "0", true),
                rule("IND_EMPLOYMENT", RuleType.EMPLOYMENT_ACCEPTABLE, null, true),
                rule("REPAYMENT_DEFAULT", RuleType.REPAYMENT_DEFAULT, null, false),
                rule("REPAYMENT_DELINQUENT", RuleType.REPAYMENT_DELINQUENT, null, true),
                rule("COLLATERAL_COVERAGE_MIN", RuleType.COLLATERAL_COVERAGE_MIN, "1.000000", true),
                rule("BUREAU_REQUIRED", RuleType.BUREAU_REQUIRED, null, false),
                rule("BUREAU_SCORE_MIN", RuleType.BUREAU_SCORE_MIN, "500", true),
                rule("EXPOSURE_LIMIT", RuleType.EXPOSURE_LIMIT, "5000000", true),
                rule("SCORE_THRESHOLD", RuleType.SCORE_THRESHOLD, null, true));
    }

    private static List<CreditRule> smeRules() {
        return List.of(
                rule("KYC_VERIFIED", RuleType.KYC_VERIFIED, null, false),
                rule("CUSTOMER_ACTIVE", RuleType.CUSTOMER_ACTIVE, null, false),
                rule("COMPLIANCE_CLEAR", RuleType.COMPLIANCE_CLEAR, null, false),
                rule("NOT_BLACKLISTED", RuleType.NOT_BLACKLISTED, null, false),
                rule("PRODUCT_ELIGIBLE", RuleType.PRODUCT_ELIGIBLE, null, false),
                rule("LOAN_LIMIT", RuleType.LOAN_LIMIT, null, false),
                rule("SME_DSCR_MIN", RuleType.DSCR_MIN, "1.250000", true),
                rule("SME_BUSINESS_AGE", RuleType.BUSINESS_AGE_MIN, "12", true),
                rule("SME_FINANCIALS", RuleType.FINANCIAL_STATEMENTS, null, true),
                rule("REPAYMENT_DEFAULT", RuleType.REPAYMENT_DEFAULT, null, false),
                rule("BUREAU_SCORE_MIN", RuleType.BUREAU_SCORE_MIN, "500", true),
                rule("EXPOSURE_LIMIT", RuleType.EXPOSURE_LIMIT, "5000000", true));
    }

    private static CreditRule rule(String code, RuleType type, String threshold, boolean exceptionAllowed) {
        DecisionOutcome failure = type == RuleType.COMPLIANCE_CLEAR || type == RuleType.NOT_BLACKLISTED
                ? DecisionOutcome.COMPLIANCE_HOLD
                : type == RuleType.BUREAU_REQUIRED ? DecisionOutcome.PENDING_DATA : DecisionOutcome.AUTO_REJECT;
        return new CreditRule(UUID.randomUUID(), code, code, code, "ANY", null, type, true, exceptionAllowed,
                threshold == null ? null : new BigDecimal(threshold), failure, "POL-2026.1", true);
    }

    private static BigDecimal money(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static UUID version(String id) {
        return UUID.fromString(id);
    }
}
