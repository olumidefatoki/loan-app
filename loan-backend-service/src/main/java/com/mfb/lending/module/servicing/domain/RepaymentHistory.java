package com.mfb.lending.module.servicing.domain;

import java.time.Instant;
import java.util.UUID;

public record RepaymentHistory(
        UUID id,
        UUID repaymentId,
        String fromStatus,
        String toStatus,
        String reason,
        Instant occurredAt
) {
}
