package com.mfb.lending.module.servicing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.mfb.lending.module.servicing.calculation.HolidayRule;

public record RestructureCommand(
        UUID loanAccountId,
        int tenure,
        BigDecimal interestRate,
        boolean capitalizeInterest,
        Integer repaymentDayOfMonth,
        HolidayRule holidayRule,
        String reason,
        LocalDate valueDate,
        UUID actorId,
        String actorName
) {
}
