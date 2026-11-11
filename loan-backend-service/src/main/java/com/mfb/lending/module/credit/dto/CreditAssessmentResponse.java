package com.mfb.lending.module.credit.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.module.credit.domain.AssessmentFacts;
import com.mfb.lending.module.credit.domain.CalculationSet;
import com.mfb.lending.module.credit.domain.DecisionReason;

public record CreditAssessmentResponse(
        UUID id,
        String reference,
        UUID applicationId,
        UUID customerId,
        UUID productId,
        UUID productVersionId,
        String segment,
        String status,
        String decision,
        BigDecimal score,
        String riskCategory,
        UUID scoringModelId,
        UUID scoringModelVersionId,
        String policyVersion,
        AssessmentFacts inputs,
        CalculationSet calculations,
        List<DecisionReason> reasons,
        String analystRecommendation,
        String analystJustification,
        UUID assignedTo,
        int revision,
        Instant createdAt,
        Instant updatedAt
) {
}
