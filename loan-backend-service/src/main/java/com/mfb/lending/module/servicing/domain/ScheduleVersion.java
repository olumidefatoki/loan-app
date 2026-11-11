package com.mfb.lending.module.servicing.domain;

import java.time.Instant;
import java.util.UUID;

public record ScheduleVersion(
        UUID id,
        UUID loanAccountId,
        int versionNumber,
        String status,
        UUID previousScheduleId,
        String engineVersion,
        String servicingVersion,
        String fingerprint,
        String parameters,
        String reason,
        Instant createdAt,
        String createdBy
) {
}
