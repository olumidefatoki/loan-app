package com.mfb.lending.module.credit.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.module.credit.domain.DecisionThresholds;
import com.mfb.lending.module.credit.domain.RiskBand;
import com.mfb.lending.module.credit.domain.ScoringComponentSpec;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateScoringModelRequest(
        @NotBlank @Size(min = 2, max = 40) String code,
        @NotBlank @Size(max = 160) String name,
        @NotBlank String segment,
        UUID productId,
        @NotBlank @Size(max = 40) String policyVersion,
        @NotNull List<ScoringComponentSpec> components,
        Map<String, Map<String, BigDecimal>> categoricalScores,
        @NotNull List<RiskBand> riskCategories,
        @NotNull DecisionThresholds decisionThresholds,
        @Size(max = 500) String changeReason
) {
}
