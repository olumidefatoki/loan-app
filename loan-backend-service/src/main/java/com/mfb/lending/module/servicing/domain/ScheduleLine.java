package com.mfb.lending.module.servicing.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ScheduleLine(
        UUID id,
        UUID scheduleId,
        int number,
        LocalDate contractualDueDate,
        LocalDate dueDate,
        BigDecimal openingBalance,
        BigDecimal principalDue,
        BigDecimal interestDue,
        BigDecimal instalmentAmount,
        BigDecimal closingBalance,
        BigDecimal principalPaid,
        BigDecimal interestPaid,
        BigDecimal penaltyAccrued,
        String status
) {
}
