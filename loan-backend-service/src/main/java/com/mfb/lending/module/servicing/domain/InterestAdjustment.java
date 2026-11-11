package com.mfb.lending.module.servicing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InterestAdjustment(
        UUID id,
        UUID loanAccountId,
        BigDecimal amount,
        String reason,
        UUID journalId,
        String status,
        UUID requestedBy,
        UUID approvedBy,
        Instant createdAt
) {
}
