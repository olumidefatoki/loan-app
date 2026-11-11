package com.mfb.lending.module.servicing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LoanAccount(
        UUID id,
        String reference,
        UUID applicationId,
        UUID customerId,
        UUID branchId,
        UUID productId,
        UUID productVersionId,
        UUID offerId,
        String currencyCode,
        LoanAccountStatus status,
        BigDecimal originalPrincipal,
        BigDecimal outstandingPrincipal,
        BigDecimal accruedInterest,
        BigDecimal outstandingInterest,
        BigDecimal outstandingFees,
        BigDecimal outstandingPenalties,
        BigDecimal overpayment,
        LocalDate disbursementDate,
        LocalDate maturityDate,
        LocalDate accrualStart,
        LocalDate lastAccruedThrough,
        int gracePeriodDays,
        int penaltyGraceDays,
        String penaltyBasis,
        BigDecimal penaltyRate,
        BigDecimal penaltyAmount,
        String earlyRepaymentRule,
        BigDecimal earlyRepaymentFeeRate,
        String interestMethod,
        String interestPeriod,
        BigDecimal interestRate,
        String repaymentFrequency,
        String dayCountConvention,
        int scale,
        String roundingMethod,
        String accrualFrequency,
        Integer repaymentDayOfMonth,
        String holidayRule,
        String coreDisbursementReference,
        UUID currentScheduleId,
        int scheduleVersion,
        int version,
        Instant activatedAt,
        Instant closedAt,
        Instant createdAt,
        Instant updatedAt,
        String createdBy
) {

    public BigDecimal totalOutstanding() {
        return outstandingPrincipal.add(outstandingInterest).add(outstandingFees).add(outstandingPenalties);
    }

    public LoanAccount withPosition(Position position) {
        return new LoanAccount(
                id, reference, applicationId, customerId, branchId, productId, productVersionId, offerId, currencyCode,
                position.status(), originalPrincipal, position.outstandingPrincipal(), position.accruedInterest(),
                position.outstandingInterest(), position.outstandingFees(), position.outstandingPenalties(),
                position.overpayment(), disbursementDate, position.maturityDate(), position.accrualStart(),
                position.lastAccruedThrough(), gracePeriodDays, penaltyGraceDays, penaltyBasis, penaltyRate, penaltyAmount,
                earlyRepaymentRule, earlyRepaymentFeeRate, interestMethod, interestPeriod, position.interestRate(),
                repaymentFrequency, dayCountConvention, scale, roundingMethod, accrualFrequency, repaymentDayOfMonth,
                holidayRule, coreDisbursementReference, position.currentScheduleId(), position.scheduleVersion(),
                position.version(), activatedAt, position.closedAt(), createdAt, position.updatedAt(), createdBy);
    }

    public Position position() {
        return new Position(
                status, outstandingPrincipal, accruedInterest, outstandingInterest, outstandingFees, outstandingPenalties,
                overpayment, maturityDate, accrualStart, lastAccruedThrough, currentScheduleId, scheduleVersion,
                interestRate, closedAt, version, updatedAt);
    }

    public record Position(
            LoanAccountStatus status,
            BigDecimal outstandingPrincipal,
            BigDecimal accruedInterest,
            BigDecimal outstandingInterest,
            BigDecimal outstandingFees,
            BigDecimal outstandingPenalties,
            BigDecimal overpayment,
            LocalDate maturityDate,
            LocalDate accrualStart,
            LocalDate lastAccruedThrough,
            UUID currentScheduleId,
            int scheduleVersion,
            BigDecimal interestRate,
            Instant closedAt,
            int version,
            Instant updatedAt
    ) {
    }
}
