package com.mfb.lending.module.credit.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.mfb.lending.module.credit.domain.DecisionThresholds;
import com.mfb.lending.module.credit.domain.RiskBand;
import com.mfb.lending.module.credit.domain.ScoringComponentSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ScoringSpecRequest(
        @NotBlank @Size(max = 40) String policyVersion,
        @NotNull List<ScoringComponentSpec> components,
        Map<String, Map<String, BigDecimal>> categoricalScores,
        @NotNull List<RiskBand> riskCategories,
        @NotNull DecisionThresholds decisionThresholds,
        @Size(max = 500) String changeReason
) {
}
