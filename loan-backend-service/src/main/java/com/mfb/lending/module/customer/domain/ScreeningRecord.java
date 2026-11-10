package com.mfb.lending.module.customer.domain;

import java.time.Instant;
import java.util.UUID;

public record ScreeningRecord(
        UUID id,
        UUID customerId,
        ScreeningType type,
        ScreeningOutcome outcome,
        String provider,
        String detail,
        Instant screenedAt
) {
}
