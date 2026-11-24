package com.mfb.lending.module.reporting.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ReportingModels {

    private ReportingModels() {
    }

    public record ApplicationCounts(
            long total,
            long pending,
            long awaitingApproval,
            long approved,
            long rejected,
            long disbursed,
            long withdrawn,
            long cancelled,
            long expired
    ) {
        public static ApplicationCounts empty() {
            return new ApplicationCounts(0, 0, 0, 0, 0, 0, 0, 0, 0);
        }
    }

    public record DecisionMetrics(long approved, long rejected, BigDecimal turnaroundDays, long turnaroundSample) {
        public static DecisionMetrics empty() {
            return new DecisionMetrics(0, 0, null, 0);
        }
    }

    public record StockMetrics(
            long loanCount,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal outstandingFees,
            BigDecimal outstandingPenalties,
            BigDecimal overduePrincipal,
            long overdueCount,
            long overdueStatusCount,
            BigDecimal par30Principal,
            long par30Count,
            BigDecimal par60Principal,
            long par60Count,
            BigDecimal par90Principal,
            long par90Count,
            long bucketCurrent,
            long bucket1To30,
            long bucket31To60,
            long bucket61To90,
            long bucketAbove90,
            String stockBasis
    ) {
        public static StockMetrics empty(String stockBasis) {
            BigDecimal zero = BigDecimal.ZERO;
            return new StockMetrics(0, zero, zero, zero, zero, zero, 0, 0, zero, 0, zero, 0, zero, 0, 0, 0, 0, 0, 0, stockBasis);
        }
    }

    public record FlowMetrics(
            BigDecimal generalLedgerDisbursement,
            BigDecimal subledgerDisbursement,
            long disbursementCount,
            BigDecimal generalLedgerCollection,
            BigDecimal subledgerCollection,
            BigDecimal contractualDue,
            BigDecimal generalLedgerInterestIncome,
            BigDecimal accruedInterest,
            BigDecimal interestReversal,
            BigDecimal generalLedgerFeeIncome,
            BigDecimal disbursementFee,
            BigDecimal chargeFee
    ) {
        public BigDecimal interestSubledger() {
            return accruedInterest.subtract(interestReversal);
        }

        public BigDecimal feeSubledger() {
            return disbursementFee.add(chargeFee);
        }

        public static FlowMetrics empty() {
            BigDecimal zero = BigDecimal.ZERO;
            return new FlowMetrics(zero, zero, 0, zero, zero, zero, zero, zero, zero, zero, zero, zero);
        }
    }

    public record BalanceMetrics(
            BigDecimal subledgerPrincipal,
            BigDecimal generalLedgerPrincipal,
            BigDecimal subledgerInterest,
            BigDecimal generalLedgerInterest,
            BigDecimal subledgerFees,
            BigDecimal generalLedgerFees,
            BigDecimal subledgerPenalties,
            BigDecimal generalLedgerPenalties,
            BigDecimal subledgerOverpayment,
            BigDecimal generalLedgerOverpayment
    ) {
        public static BalanceMetrics empty() {
            BigDecimal zero = BigDecimal.ZERO;
            return new BalanceMetrics(zero, zero, zero, zero, zero, zero, zero, zero, zero, zero);
        }
    }

    public record WriteOffMetrics(
            BigDecimal generalLedgerExpense,
            long uncollectibleCases,
            BigDecimal uncollectibleExposure,
            BigDecimal recoveryAmount,
            BigDecimal linkedPostedRepayment,
            long recoveryCount,
            long unpostedRecoveries
    ) {
        public static WriteOffMetrics empty() {
            return new WriteOffMetrics(BigDecimal.ZERO, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }
    }

    public record GroupMetrics(
            UUID groupId,
            String code,
            String name,
            long loanCount,
            BigDecimal outstandingPrincipal,
            BigDecimal overduePrincipal,
            BigDecimal par30Principal,
            BigDecimal par60Principal,
            BigDecimal par90Principal,
            BigDecimal disbursementPrincipal,
            BigDecimal collectionCash,
            BigDecimal income,
            long applications,
            long pendingApplications
    ) {
        public static GroupMetrics stock(UUID groupId, String code, String name, long loanCount, BigDecimal outstanding,
                BigDecimal overdue, BigDecimal par30, BigDecimal par60, BigDecimal par90) {
            return new GroupMetrics(groupId, code, name, loanCount, outstanding, overdue, par30, par60, par90,
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0);
        }
    }

    public record MeasureRow(String measure, BigDecimal subledger, BigDecimal generalLedger, BigDecimal variance, String note) {
    }

    public record LoanLine(
            String journalReference,
            String loanReference,
            LocalDate eventDate,
            BigDecimal amount,
            BigDecimal principal,
            BigDecimal interest,
            BigDecimal fees,
            BigDecimal penalties,
            String status,
            String event,
            String branch,
            String product,
            String segment,
            String security,
            String risk,
            String customerName,
            Integer daysPastDue,
            String bucket
    ) {
    }

    public record DetailPage(List<LoanLine> lines, int page, int size, long total) {
    }

    public record CustomerLoanPage(List<CustomerLoan> loans, int page, int size, long total) {
    }

    public record CustomerRepaymentPage(List<CustomerRepayment> repayments, int page, int size, long total) {
    }

    public record CustomerPosition(
            long activeLoans,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal outstandingFees,
            BigDecimal outstandingPenalties,
            LocalDate nextRepaymentDate,
            BigDecimal nextRepaymentAmount,
            BigDecimal principalPaid,
            BigDecimal interestPaid,
            BigDecimal feesPaid,
            BigDecimal penaltiesPaid,
            LocalDate lastPaymentDate
    ) {
    }

    public record CustomerLoan(
            String reference,
            String productCode,
            String status,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal outstandingFees,
            BigDecimal outstandingPenalties,
            LocalDate maturityDate,
            LocalDate nextDueDate,
            BigDecimal nextDueAmount
    ) {
    }

    public record CustomerRepayment(
            String reference,
            String loanReference,
            LocalDate valueDate,
            BigDecimal amount,
            String status,
            String channel
    ) {
    }

    public record CustomerApplication(
            String reference,
            String productCode,
            String status,
            BigDecimal amount,
            String currency,
            Instant submittedAt
    ) {
    }

    public record ExportJob(
            UUID id,
            ReportCode reportCode,
            ExportFormat format,
            ExportStatus status,
            String filters,
            UUID requestedBy,
            String requestedByName,
            String fileName,
            String contentType,
            String storagePath,
            Integer rowCount,
            String tokenHash,
            String errorMessage,
            Instant expiresAt,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Instant downloadedAt
    ) {
    }

    public record SnapshotRow(
            int loanCount,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal overduePrincipal,
            BigDecimal par30Ratio,
            BigDecimal par60Ratio,
            BigDecimal par90Ratio,
            BigDecimal defaultRate,
            BigDecimal disbursementPrincipal,
            BigDecimal collectionCash,
            BigDecimal generalLedgerPrincipal,
            BigDecimal principalVariance
    ) {
    }
}
