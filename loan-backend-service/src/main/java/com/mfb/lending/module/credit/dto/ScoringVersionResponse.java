package com.mfb.lending.module.credit.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.module.credit.domain.DecisionThresholds;
import com.mfb.lending.module.credit.domain.RiskBand;
import com.mfb.lending.module.credit.domain.ScoringWeights;

public record ScoringVersionResponse(
        UUID id,
        int versionNumber,
        String status,
        ScoringWeights weights,
        List<RiskBand> riskCategories,
        DecisionThresholds decisionThresholds,
        String policyVersion,
        String changeReason,
        Instant activatedAt
) {
}
