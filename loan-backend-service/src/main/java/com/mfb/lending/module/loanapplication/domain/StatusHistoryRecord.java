package com.mfb.lending.module.loanapplication.domain;

import java.time.Instant;
import java.util.UUID;

public record StatusHistoryRecord(
        UUID id,
        UUID applicationId,
        ApplicationStatus fromStatus,
        ApplicationStatus toStatus,
        String reason,
        UUID actorId,
        Instant occurredAt
) {
}
