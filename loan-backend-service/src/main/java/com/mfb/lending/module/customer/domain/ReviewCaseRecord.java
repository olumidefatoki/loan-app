package com.mfb.lending.module.customer.domain;

import java.time.Instant;
import java.util.UUID;

public record ReviewCaseRecord(
        UUID id,
        UUID customerId,
        ReviewCaseType type,
        String status,
        boolean mandatoryHold,
        String summary,
        Instant openedAt
) {
}
