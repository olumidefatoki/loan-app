package com.mfb.lending.module.reporting.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.module.reporting.calculation.ReportingRules;
import com.mfb.lending.module.reporting.domain.ReportingModels.ApplicationCounts;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerApplication;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerLoan;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerRepayment;
import com.mfb.lending.module.reporting.domain.ReportingModels.DetailPage;
import com.mfb.lending.module.reporting.domain.ReportingModels.MeasureRow;

public final class ReportResponses {

    private ReportResponses() {
    }

    public record StatementSummary(
            BigDecimal principalPaid,
            BigDecimal interestPaid,
            BigDecimal feesPaid,
            BigDecimal penaltiesPaid,
            LocalDate lastPaymentDate
    ) {
    }

    public record CustomerDashboard(
            String currency,
            long activeLoans,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal outstandingFees,
            BigDecimal outstandingPenalties,
            LocalDate nextRepaymentDate,
            BigDecimal nextRepaymentAmount,
            StatementSummary statement,
            List<CustomerApplication> applications,
            long applicationCount
    ) {
    }

    public record CustomerLoanPage(List<CustomerLoan> loans, int page, int size, long total) {
    }

    public record CustomerRepaymentPage(List<CustomerRepayment> repayments, int page, int size, long total, boolean periodBounded, LocalDate periodStart, LocalDate periodEnd) {
    }

    public record GroupRow(
            String code,
            String name,
            long loanCount,
            BigDecimal outstandingPrincipal,
            BigDecimal overduePrincipal,
            BigDecimal par30,
            BigDecimal par60,
            BigDecimal par90,
            BigDecimal disbursementPrincipal,
            BigDecimal collectionCash,
            BigDecimal income,
            long applications,
            long pendingApplications
    ) {
    }

    public record OperationsDashboard(
            String currency,
            LocalDate stockDate,
            String stockBasis,
            boolean periodBounded,
            LocalDate periodStart,
            LocalDate periodEnd,
            ApplicationCounts applications,
            long disbursedLoans,
            BigDecimal disbursementPrincipal,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal overduePrincipal,
            BigDecimal postedRepaymentCash,
            BigDecimal contractualDue,
            BigDecimal collectionRate,
            boolean collectionRateDefined
    ) {
    }

    public record CreditDashboard(
            boolean periodBounded,
            LocalDate periodStart,
            LocalDate periodEnd,
            ApplicationCounts applications,
            long approvedDecisions,
            long rejectedDecisions,
            BigDecimal approvalRate,
            boolean approvalRateDefined,
            BigDecimal rejectionRate,
            boolean rejectionRateDefined,
            BigDecimal turnaroundDays,
            long turnaroundSample,
            BigDecimal averageLoanSize,
            List<GroupRow> riskDistribution,
            String approvalRule,
            String turnaroundRule
    ) {
        public CreditDashboard {
            approvalRule = ReportingRules.APPROVAL_RATE;
            turnaroundRule = ReportingRules.TURNAROUND;
        }
    }

    public record CollectionsDashboard(
            LocalDate stockDate,
            String stockBasis,
            BigDecimal outstandingPrincipal,
            BigDecimal overduePrincipal,
            long overdueCount,
            BigDecimal par30,
            BigDecimal par60,
            BigDecimal par90,
            BigDecimal par30Principal,
            BigDecimal par60Principal,
            BigDecimal par90Principal,
            long par30Count,
            long par60Count,
            long par90Count,
            BigDecimal defaultRate,
            long nonPerformingLoans,
            BigDecimal nonPerformingPrincipal,
            long overdueStatusCount,
            BigDecimal postedRepaymentCash,
            BigDecimal contractualDue,
            BigDecimal collectionRate,
            boolean collectionRateDefined,
            long openCases,
            BigDecimal recoveryAmount,
            long bucketCurrent,
            long bucket1To30,
            long bucket31To60,
            long bucket61To90,
            long bucketAbove90,
            String parRule,
            String bucketRule
    ) {
        public CollectionsDashboard {
            parRule = ReportingRules.PAR;
            bucketRule = ReportingRules.BUCKET;
        }
    }

    public record BranchDashboard(boolean generalLedger, List<GroupRow> branches) {
    }

    public record Rules(
            String par,
            String collectionRate,
            String defaultRate,
            String approvalRate,
            String turnaround,
            String averageLoanSize,
            String disbursement,
            String collections,
            String security
    ) {
        public static Rules standard() {
            return new Rules(ReportingRules.PAR, ReportingRules.COLLECTION_RATE, ReportingRules.DEFAULT_RATE,
                    ReportingRules.APPROVAL_RATE, ReportingRules.TURNAROUND, ReportingRules.AVERAGE_LOAN,
                    ReportingRules.DISBURSEMENT, ReportingRules.COLLECTIONS, ReportingRules.SECURITY);
        }
    }

    public record PortfolioReport(
            String currency,
            LocalDate stockDate,
            String stockBasis,
            boolean periodBounded,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate latestSnapshot,
            BigDecimal totalDisbursements,
            long disbursedLoans,
            BigDecimal averageLoanSize,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal totalRepaymentCollections,
            BigDecimal collectionRate,
            boolean collectionRateDefined,
            BigDecimal par30,
            BigDecimal par60,
            BigDecimal par90,
            BigDecimal par30Principal,
            BigDecimal par60Principal,
            BigDecimal par90Principal,
            BigDecimal defaultRate,
            long nonPerformingLoans,
            BigDecimal nonPerformingPrincipal,
            BigDecimal approvalRate,
            boolean approvalRateDefined,
            BigDecimal rejectionRate,
            boolean rejectionRateDefined,
            BigDecimal turnaroundDays,
            long turnaroundSample,
            List<GroupRow> individualAndSme,
            List<GroupRow> securedAndUnsecured,
            List<GroupRow> creditRisk,
            List<GroupRow> products,
            List<GroupRow> branches,
            Rules rules
    ) {
    }

    public record MoneyReport(BigDecimal generalLedger, BigDecimal subledger, BigDecimal variance, long count, String rule, DetailPage lines) {
    }

    public record ParSummary(
            LocalDate stockDate,
            String stockBasis,
            BigDecimal denominator,
            long loanCount,
            BigDecimal par30,
            BigDecimal par60,
            BigDecimal par90,
            BigDecimal par30Principal,
            BigDecimal par60Principal,
            BigDecimal par90Principal,
            String rule
    ) {
    }

    public record DefaultReport(
            BigDecimal defaultRate,
            long nonPerformingLoans,
            BigDecimal nonPerformingPrincipal,
            BigDecimal denominator,
            String rule,
            DetailPage lines
    ) {
    }

    public record GroupReport(String rule, List<GroupRow> rows) {
    }

    public record IncomeReport(String accountCode, String accountName, BigDecimal generalLedger, BigDecimal subledger, BigDecimal variance, String rule, List<GroupRow> products) {
    }

    public record DelinquencyReport(
            long bucketCurrent,
            long bucket1To30,
            long bucket31To60,
            long bucket61To90,
            long bucketAbove90,
            BigDecimal overduePrincipal,
            String rule,
            DetailPage lines
    ) {
    }

    public record WriteOffReport(
            String accountCode,
            BigDecimal generalLedgerExpense,
            long uncollectibleCases,
            BigDecimal uncollectibleExposure,
            BigDecimal recoveryAmount,
            BigDecimal linkedPostedRepayment,
            BigDecimal recoveryVariance,
            long recoveryCount,
            long unpostedRecoveries,
            String rule,
            DetailPage lines
    ) {
    }

    public record ReconciliationReport(boolean balancesAreCurrent, String rule, List<MeasureRow> measures) {
    }

    public record ExportResponse(
            UUID id,
            String report,
            String format,
            String status,
            Instant expiresAt,
            Integer rowCount,
            String fileName,
            String downloadPath,
            String token
    ) {
    }
}
