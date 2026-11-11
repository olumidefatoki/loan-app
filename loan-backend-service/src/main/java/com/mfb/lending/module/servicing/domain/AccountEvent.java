package com.mfb.lending.module.servicing.domain;

import java.time.Instant;
import java.util.UUID;

public record AccountEvent(
        UUID id,
        UUID loanAccountId,
        String eventType,
        String payload,
        UUID actorId,
        Instant occurredAt
) {
}
