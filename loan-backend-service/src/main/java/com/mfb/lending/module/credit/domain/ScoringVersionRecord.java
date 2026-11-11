package com.mfb.lending.module.credit.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScoringVersionRecord(
        UUID id,
        UUID modelId,
        int versionNumber,
        ModelStatus status,
        ScoringWeights weights,
        List<RiskBand> riskCategories,
        DecisionThresholds thresholds,
        String policyVersion,
        String changeReason,
        Instant activatedAt,
        Instant createdAt,
        String createdBy
) {

    public ScoringVersionRecord {
        riskCategories = riskCategories == null ? List.of() : List.copyOf(riskCategories);
    }
}
