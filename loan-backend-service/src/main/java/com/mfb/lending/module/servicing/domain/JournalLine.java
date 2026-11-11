package com.mfb.lending.module.servicing.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record JournalLine(
        UUID id,
        UUID journalId,
        int lineNumber,
        String accountCode,
        String direction,
        BigDecimal amount,
        String currencyCode,
        String narration
) {
}
