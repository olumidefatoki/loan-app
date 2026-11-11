package com.mfb.lending.module.credit.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.module.credit.domain.AssessmentFacts;
import com.mfb.lending.module.credit.domain.BureauView;
import com.mfb.lending.module.credit.domain.CalculationSet;
import com.mfb.lending.module.credit.domain.DecisionThresholds;
import com.mfb.lending.module.credit.domain.RiskBand;
import com.mfb.lending.module.credit.domain.ScoreComponentResult;
import com.mfb.lending.module.credit.domain.ScoreDirection;
import com.mfb.lending.module.credit.domain.ScoreMetric;
import com.mfb.lending.module.credit.domain.ScoreResult;
import com.mfb.lending.module.credit.domain.ScoringComponentSpec;
import com.mfb.lending.module.credit.domain.ScoringWeights;

public final class CreditScoreCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100.00");
    private static final int SCORE_SCALE = 2;

    private CreditScoreCalculator() {
    }

    public static ScoreResult score(
            UUID modelId,
            UUID modelVersionId,
            String policyVersion,
            ScoringWeights weights,
            List<RiskBand> riskCategories,
            AssessmentFacts facts,
            BureauView bureau,
            CalculationSet calculations) {
        List<ScoreComponentResult> components = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean complete = true;
        for (ScoringComponentSpec spec : weights.components()) {
            MetricValue metric = metric(spec, facts, bureau, calculations);
            if (metric.value() == null && spec.direction() != ScoreDirection.CATEGORICAL) {
                complete = false;
                missing.add(spec.metric());
                components.add(new ScoreComponentResult(
                        spec.code(),
                        spec.metric(),
                        null,
                        null,
                        spec.weight(),
                        null,
                        spec.code() + " was not scored because " + spec.metric() + " is missing."));
                continue;
            }
            if (spec.direction() == ScoreDirection.CATEGORICAL && metric.text() == null) {
                complete = false;
                missing.add(spec.metric());
                components.add(new ScoreComponentResult(
                        spec.code(),
                        spec.metric(),
                        null,
                        null,
                        spec.weight(),
                        null,
                        spec.code() + " was not scored because " + spec.metric() + " is missing."));
                continue;
            }
            BigDecimal raw = componentScore(spec, metric, weights.categoricalScores());
            BigDecimal weighted = raw.multiply(spec.weight()).setScale(4, RoundingMode.HALF_UP);
            total = total.add(weighted);
            components.add(new ScoreComponentResult(
                    spec.code(),
                    spec.metric(),
                    metric.value(),
                    raw,
                    spec.weight(),
                    weighted,
                    explain(spec, metric, raw, weighted)));
        }
        if (!complete) {
            return new ScoreResult(
                    modelId,
                    modelVersionId,
                    policyVersion,
                    null,
                    null,
                    false,
                    components,
                    missing,
                    "The score is incomplete because " + String.join(", ", missing) + " is missing.");
        }
        BigDecimal score = total.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        String risk = riskCategory(riskCategories, score);
        String explanation = "Score " + score.toPlainString() + " is in risk category " + risk
                + " under policy " + policyVersion + ".";
        return new ScoreResult(modelId, modelVersionId, policyVersion, score, risk, true, components, List.of(), explanation);
    }

    public static BigDecimal componentScore(
            ScoringComponentSpec spec,
            BigDecimal numericValue,
            String categoricalValue,
            Map<String, Map<String, BigDecimal>> categoricalScores) {
        return componentScore(spec, new MetricValue(numericValue, categoricalValue), categoricalScores);
    }

    private static BigDecimal componentScore(
            ScoringComponentSpec spec,
            MetricValue metric,
            Map<String, Map<String, BigDecimal>> categoricalScores) {
        if (spec.direction() == ScoreDirection.CATEGORICAL) {
            Map<String, BigDecimal> bands = categoricalScores.getOrDefault(spec.metric(), Map.of());
            BigDecimal mapped = bands.get(metric.text());
            if (mapped == null) {
                mapped = bands.getOrDefault("UNKNOWN", BigDecimal.ZERO);
            }
            return mapped.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal value = metric.value();
        BigDecimal best = spec.best();
        BigDecimal worst = spec.worst();
        if (spec.direction() == ScoreDirection.HIGHER_IS_BETTER) {
            if (value.compareTo(best) >= 0) {
                return HUNDRED;
            }
            if (value.compareTo(worst) <= 0) {
                return BigDecimal.ZERO.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
            }
            return value.subtract(worst)
                    .divide(best.subtract(worst), 6, RoundingMode.HALF_UP)
                    .multiply(HUNDRED)
                    .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        }
        if (value.compareTo(best) <= 0) {
            return HUNDRED;
        }
        if (value.compareTo(worst) >= 0) {
            return BigDecimal.ZERO.setScale(SCORE_SCALE, RoundingMode.HALF_UP);
        }
        return worst.subtract(value)
                .divide(worst.subtract(best), 6, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(SCORE_SCALE, RoundingMode.HALF_UP);
    }

    public static boolean meets(DecisionThresholds thresholds, BigDecimal score, BigDecimal minimum) {
        return score != null && score.compareTo(minimum) >= 0 && thresholds != null;
    }

    private static String riskCategory(List<RiskBand> bands, BigDecimal score) {
        return bands.stream()
                .filter(band -> band.minimumScore() != null && score.compareTo(band.minimumScore()) >= 0)
                .max(Comparator.comparing(RiskBand::minimumScore))
                .map(RiskBand::code)
                .orElse("UNCLASSIFIED");
    }

    private static MetricValue metric(
            ScoringComponentSpec spec,
            AssessmentFacts facts,
            BureauView bureau,
            CalculationSet calculations) {
        ScoreMetric metric;
        try {
            metric = ScoreMetric.valueOf(spec.metric());
        } catch (IllegalArgumentException ex) {
            return new MetricValue(null, null);
        }
        return switch (metric) {
            case DTI -> new MetricValue(calculations.dti().output(), null);
            case DISPOSABLE_INCOME_RATIO -> new MetricValue(
                    CreditCalculations.ratio(calculations.disposableIncome().output(), facts.monthlyGrossIncome()), null);
            case EMPLOYMENT_MONTHS -> new MetricValue(decimal(facts.employmentMonths()), null);
            case TURNOVER_TO_INCOME -> new MetricValue(
                    CreditCalculations.ratio(facts.bankAccountTurnover(), facts.monthlyGrossIncome()), null);
            case REPAYMENT_HISTORY -> new MetricValue(null, facts.repaymentHistory() == null ? null : facts.repaymentHistory().name());
            case BUREAU_SCORE -> new MetricValue(bureau == null || bureau.score() == null ? null : new BigDecimal(bureau.score()), null);
            case DSCR -> new MetricValue(calculations.dscr().output(), null);
            case CASH_FLOW_MARGIN -> new MetricValue(
                    CreditCalculations.ratio(facts.netOperatingCashFlow(), facts.monthlyRevenue()), null);
            case BUSINESS_AGE_MONTHS -> new MetricValue(decimal(facts.businessAgeMonths()), null);
            case ACCOUNT_TURNOVER_TO_REVENUE -> new MetricValue(
                    CreditCalculations.ratio(facts.accountTurnover(), facts.monthlyRevenue()), null);
            case SECTOR -> new MetricValue(null, normalize(facts.businessSector()));
            case BUREAU_EXPOSURE_RATIO -> new MetricValue(
                    CreditCalculations.ratio(bureau == null ? null : bureau.existingExposure(), facts.monthlyRevenue()), null);
            case COLLATERAL_COVERAGE -> new MetricValue(calculations.collateralCoverage().output(), null);
        };
    }

    private static BigDecimal decimal(Integer value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String explain(ScoringComponentSpec spec, MetricValue metric, BigDecimal raw, BigDecimal weighted) {
        String shown = spec.direction() == ScoreDirection.CATEGORICAL ? metric.text() : plain(metric.value());
        return spec.code() + " input " + shown + " scores " + raw.toPlainString()
                + " of 100. Weight " + spec.weight().toPlainString()
                + " contributes " + weighted.toPlainString() + ".";
    }

    private static String plain(BigDecimal value) {
        return value == null ? "missing" : value.stripTrailingZeros().toPlainString();
    }

    private record MetricValue(BigDecimal value, String text) {
    }
}
