package com.mfb.lending.module.collections.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.mfb.lending.module.servicing.application.LoanServicing;
import com.mfb.lending.module.servicing.calculation.HolidayRule;

public interface ScheduleRevisionPort {

    Revision apply(RevisionRequest request);

    BigDecimal quotedInstalment(RevisionRequest request);

    LoanServicing.Reconciliation reconcile(UUID loanAccountId);

    record RevisionRequest(
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
    }

    record Revision(
            UUID previousScheduleId,
            int previousVersion,
            UUID newScheduleId,
            int newVersion,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal outstandingFees,
            BigDecimal outstandingPenalties,
            LocalDate maturity
    ) {
    }
}
