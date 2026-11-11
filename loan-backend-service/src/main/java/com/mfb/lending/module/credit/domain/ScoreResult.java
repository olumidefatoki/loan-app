package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ScoreResult(
        UUID modelId,
        UUID modelVersionId,
        String policyVersion,
        BigDecimal score,
        String riskCategory,
        boolean complete,
        List<ScoreComponentResult> components,
        List<String> missingMetrics,
        String explanation
) {

    public ScoreResult {
        components = components == null ? List.of() : List.copyOf(components);
        missingMetrics = missingMetrics == null ? List.of() : List.copyOf(missingMetrics);
    }
}
