package com.mfb.lending.module.servicing.domain;

import java.time.Instant;
import java.util.UUID;

public record JournalHeader(
        UUID id,
        String reference,
        UUID loanAccountId,
        String eventCode,
        String currencyCode,
        JournalStatus status,
        UUID reversalOf,
        boolean requiresApproval,
        UUID requestedBy,
        UUID approvedBy,
        Instant postedAt,
        Instant createdAt
) {
}
