package com.mfb.lending.module.servicing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record BalanceSnapshot(
        UUID id,
        UUID loanAccountId,
        LocalDate valueDate,
        BigDecimal outstandingPrincipal,
        BigDecimal accruedInterest,
        BigDecimal outstandingInterest,
        BigDecimal outstandingFees,
        BigDecimal outstandingPenalties,
        BigDecimal overpayment,
        String reason,
        Instant postedAt
) {
}
