package com.mfb.lending.module.loanoffer.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ScheduleItemResponse(
        int instalmentNumber,
        LocalDate dueDate,
        BigDecimal openingBalance,
        BigDecimal principalDue,
        BigDecimal interestDue,
        BigDecimal instalmentAmount,
        BigDecimal closingBalance
) {
}
