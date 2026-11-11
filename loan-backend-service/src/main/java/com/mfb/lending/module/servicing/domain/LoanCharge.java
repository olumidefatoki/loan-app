package com.mfb.lending.module.servicing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LoanCharge(
        UUID id,
        UUID loanAccountId,
        String chargeType,
        BigDecimal amount,
        UUID journalId,
        String reason,
        Instant createdAt
) {
}
