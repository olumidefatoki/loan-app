package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreditQueueItem(
        UUID id,
        String reference,
        UUID applicationId,
        UUID customerId,
        String customerName,
        String segment,
        AssessmentStatus status,
        DecisionOutcome decision,
        BigDecimal score,
        String riskCategory,
        UUID assignedTo,
        Instant updatedAt
) {
}
