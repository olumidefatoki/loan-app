package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreditAssessmentRecord(
        UUID id,
        String reference,
        UUID applicationId,
        UUID customerId,
        UUID productId,
        UUID productVersionId,
        UUID branchId,
        String segment,
        AssessmentStatus status,
        DecisionOutcome decision,
        BigDecimal score,
        String riskCategory,
        UUID scoringModelId,
        UUID scoringModelVersionId,
        String policyVersion,
        String inputsJson,
        String calculationsJson,
        AnalystRecommendation analystRecommendation,
        String analystJustification,
        UUID recommendedBy,
        Instant recommendedAt,
        UUID assignedTo,
        int revision,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
