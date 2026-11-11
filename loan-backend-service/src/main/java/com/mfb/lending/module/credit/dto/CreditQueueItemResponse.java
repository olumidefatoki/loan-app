package com.mfb.lending.module.credit.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CreditQueueItemResponse(
        UUID id,
        String reference,
        UUID applicationId,
        UUID customerId,
        String customerName,
        String segment,
        String status,
        String decision,
        BigDecimal score,
        String riskCategory,
        UUID assignedTo,
        Instant updatedAt
) {
}
