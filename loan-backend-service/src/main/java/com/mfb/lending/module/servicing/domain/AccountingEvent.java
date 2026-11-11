package com.mfb.lending.module.servicing.domain;

import java.time.Instant;
import java.util.UUID;

public record AccountingEvent(
        UUID id,
        UUID loanAccountId,
        String eventCode,
        UUID sourceId,
        UUID journalId,
        String payload,
        Instant createdAt
) {
}
