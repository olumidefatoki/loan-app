package com.mfb.lending.module.loanoffer.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ScheduleItem(
        UUID id,
        UUID offerVersionId,
        int instalmentNumber,
        LocalDate dueDate,
        BigDecimal openingBalance,
        BigDecimal principalDue,
        BigDecimal interestDue,
        BigDecimal instalmentAmount,
        BigDecimal closingBalance
) {
}
