package com.mfb.lending.module.servicing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.mfb.lending.module.servicing.calculation.HolidayRule;

/**
 * A restructuring that collections has already approved.
 * Interest capitalisation is not available here, so accrued interest stays on the loan.
 */
public record ApprovedRestructure(
        UUID loanAccountId,
        int tenure,
        BigDecimal interestRate,
        String repaymentFrequency,
        Integer repaymentDayOfMonth,
        HolidayRule holidayRule,
        int moratoriumDays,
        BigDecimal expectedInstalment,
        String reason,
        LocalDate valueDate,
        UUID actorId,
        String actorName
) {

    public RestructureCommand command() {
        return new RestructureCommand(
                loanAccountId, tenure, interestRate, false, repaymentDayOfMonth, holidayRule, reason, valueDate, actorId, actorName);
    }
}
