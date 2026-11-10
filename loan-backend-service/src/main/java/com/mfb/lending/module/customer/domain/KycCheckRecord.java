package com.mfb.lending.module.customer.domain;

import java.time.Instant;
import java.util.UUID;

public record KycCheckRecord(
        UUID id,
        UUID customerId,
        CheckType type,
        CheckOutcome outcome,
        String provider,
        String reference,
        String detail,
        Instant completedAt
) {
}
