package com.mfb.lending.module.servicing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record InterestAccrual(
        UUID id,
        UUID loanAccountId,
        LocalDate accrualDate,
        String frequency,
        String component,
        BigDecimal amount,
        String dayCountConvention,
        UUID journalId,
        Instant createdAt
) {
}
