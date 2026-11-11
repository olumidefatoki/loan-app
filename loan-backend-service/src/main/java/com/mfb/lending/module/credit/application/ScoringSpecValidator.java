package com.mfb.lending.module.credit.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.mfb.lending.common.api.FieldViolation;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.credit.domain.DecisionThresholds;
import com.mfb.lending.module.credit.domain.RiskBand;
import com.mfb.lending.module.credit.domain.ScoreDirection;
import com.mfb.lending.module.credit.domain.ScoreMetric;
import com.mfb.lending.module.credit.domain.ScoringComponentSpec;
import com.mfb.lending.module.credit.domain.ScoringWeights;

public final class ScoringSpecValidator {

    private ScoringSpecValidator() {
    }

    public static void validate(ScoringWeights weights, List<RiskBand> riskCategories, DecisionThresholds thresholds, String policyVersion) {
        List<FieldViolation> violations = new java.util.ArrayList<>();
        if (policyVersion == null || policyVersion.isBlank()) {
            violations.add(new FieldViolation("policyVersion", "policyVersion is required"));
        }
        if (weights == null || weights.components().isEmpty()) {
            violations.add(new FieldViolation("components", "At least one scoring component is required"));
        } else {
            Set<String> codes = new HashSet<>();
            BigDecimal total = BigDecimal.ZERO;
            for (int index = 0; index < weights.components().size(); index++) {
                ScoringComponentSpec component = weights.components().get(index);
                String field = "components[" + index + "]";
                if (component.code() == null || !component.code().equals(component.code().toUpperCase(Locale.ROOT))) {
                    violations.add(new FieldViolation(field + ".code", "Component code must be uppercase"));
                } else if (!codes.add(component.code())) {
                    violations.add(new FieldViolation(field + ".code", "Component code is duplicated"));
                }
                if (component.weight() == null || component.weight().signum() <= 0 || component.weight().compareTo(BigDecimal.ONE) > 0) {
                    violations.add(new FieldViolation(field + ".weight", "Weight must be greater than 0 and at most 1"));
                } else {
                    total = total.add(component.weight());
                }
                ScoreMetric metric = null;
                try {
                    metric = component.metric() == null ? null : ScoreMetric.valueOf(component.metric());
                } catch (IllegalArgumentException ex) {
                    violations.add(new FieldViolation(field + ".metric", "Unknown scoring metric"));
                }
                if (component.direction() == null) {
                    violations.add(new FieldViolation(field + ".direction", "direction is required"));
                } else if (metric != null && metric.categorical() != (component.direction() == ScoreDirection.CATEGORICAL)) {
                    violations.add(new FieldViolation(field + ".direction", "Direction does not match the metric"));
                } else if (component.direction() != ScoreDirection.CATEGORICAL) {
                    if (component.best() == null || component.worst() == null || component.best().compareTo(component.worst()) == 0) {
                        violations.add(new FieldViolation(field + ".best", "best and worst must be different"));
                    } else if (component.direction() == ScoreDirection.HIGHER_IS_BETTER
                            && component.best().compareTo(component.worst()) <= 0) {
                        violations.add(new FieldViolation(field + ".best", "best must be above worst when higher is better"));
                    } else if (component.direction() == ScoreDirection.LOWER_IS_BETTER
                            && component.best().compareTo(component.worst()) >= 0) {
                        violations.add(new FieldViolation(field + ".best", "best must be below worst when lower is better"));
                    }
                }
            }
            if (total.setScale(4, RoundingMode.HALF_UP).compareTo(new BigDecimal("1.0000")) != 0) {
                violations.add(new FieldViolation("components", "Component weights must sum to 1"));
            }
        }
        if (riskCategories == null || riskCategories.isEmpty()) {
            violations.add(new FieldViolation("riskCategories", "At least one risk category is required"));
        } else if (riskCategories.stream().noneMatch(band -> band.minimumScore() != null
                && band.minimumScore().compareTo(BigDecimal.ZERO) == 0)) {
            violations.add(new FieldViolation("riskCategories", "One risk category must start at 0"));
        }
        if (thresholds == null || thresholds.autoApproveMinimum() == null || thresholds.referMinimum() == null) {
            violations.add(new FieldViolation("decisionThresholds", "Approval and refer thresholds are required"));
        } else if (thresholds.referMinimum().signum() < 0
                || thresholds.autoApproveMinimum().compareTo(thresholds.referMinimum()) <= 0) {
            violations.add(new FieldViolation("decisionThresholds", "The approval threshold must be above the refer threshold"));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }
}
