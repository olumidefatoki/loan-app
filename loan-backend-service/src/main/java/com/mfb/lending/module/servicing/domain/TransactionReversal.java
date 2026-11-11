package com.mfb.lending.module.servicing.domain;

import java.time.Instant;
import java.util.UUID;

public record TransactionReversal(
        UUID id,
        UUID repaymentId,
        UUID originalJournalId,
        UUID reversalJournalId,
        String reason,
        Instant createdAt
) {
}
