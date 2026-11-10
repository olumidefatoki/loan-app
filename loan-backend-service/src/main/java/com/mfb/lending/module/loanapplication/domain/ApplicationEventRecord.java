package com.mfb.lending.module.loanapplication.domain;

import java.time.Instant;
import java.util.UUID;

public record ApplicationEventRecord(
        UUID id,
        UUID applicationId,
        String eventType,
        String payload,
        UUID actorId,
        Instant occurredAt
) {
}
