package com.mfb.lending.module.servicing.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.module.servicing.application.LoanServicing.Reconciliation;
import com.mfb.lending.module.servicing.application.LoanServicing.ScheduleView;
import com.mfb.lending.module.servicing.application.LoanServicing.StatementView;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.SettlementQuote;
import com.mfb.lending.module.servicing.domain.AccountEvent;
import com.mfb.lending.module.servicing.domain.BalanceSnapshot;
import com.mfb.lending.module.servicing.domain.JournalHeader;
import com.mfb.lending.module.servicing.domain.JournalLine;
import com.mfb.lending.module.servicing.domain.LoanAccount;
import com.mfb.lending.module.servicing.domain.LoanRepayment;
import com.mfb.lending.module.servicing.domain.RepaymentAllocation;
import com.mfb.lending.module.servicing.domain.ScheduleLine;

public final class ServicingResponses {

    private ServicingResponses() {
    }

    public record LoanAccountResponse(
            UUID id,
            String reference,
            UUID applicationId,
            UUID customerId,
            UUID branchId,
            String currencyCode,
            String status,
            BigDecimal originalPrincipal,
            BigDecimal outstandingPrincipal,
            BigDecimal accruedInterest,
            BigDecimal outstandingInterest,
            BigDecimal outstandingFees,
            BigDecimal outstandingPenalties,
            BigDecimal overpayment,
            BigDecimal totalOutstanding,
            LocalDate disbursementDate,
            LocalDate maturityDate,
            LocalDate accrualStart,
            LocalDate lastAccruedThrough,
            String interestMethod,
            BigDecimal interestRate,
            String repaymentFrequency,
            String dayCountConvention,
            int scheduleVersion,
            String coreDisbursementReference,
            Instant activatedAt,
            Instant closedAt
    ) {
        public static LoanAccountResponse from(LoanAccount account) {
            return new LoanAccountResponse(
                    account.id(), account.reference(), account.applicationId(), account.customerId(), account.branchId(),
                    account.currencyCode(), account.status().name(), account.originalPrincipal(), account.outstandingPrincipal(),
                    account.accruedInterest(), account.outstandingInterest(), account.outstandingFees(), account.outstandingPenalties(),
                    account.overpayment(), account.totalOutstanding(), account.disbursementDate(), account.maturityDate(),
                    account.accrualStart(), account.lastAccruedThrough(), account.interestMethod(), account.interestRate(),
                    account.repaymentFrequency(), account.dayCountConvention(), account.scheduleVersion(),
                    account.coreDisbursementReference(), account.activatedAt(), account.closedAt());
        }
    }

    public record BalanceResponse(
            UUID loanAccountId,
            String currencyCode,
            String status,
            BigDecimal outstandingPrincipal,
            BigDecimal accruedInterest,
            BigDecimal outstandingInterest,
            BigDecimal outstandingFees,
            BigDecimal outstandingPenalties,
            BigDecimal overpayment,
            BigDecimal totalOutstanding,
            LocalDate asOf
    ) {
        public static BalanceResponse from(LoanAccount account) {
            return new BalanceResponse(
                    account.id(), account.currencyCode(), account.status().name(), account.outstandingPrincipal(),
                    account.accruedInterest(), account.outstandingInterest(), account.outstandingFees(),
                    account.outstandingPenalties(), account.overpayment(), account.totalOutstanding(), account.lastAccruedThrough());
        }
    }

    public record ScheduleLineResponse(
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
        static ScheduleLineResponse from(ScheduleLine line) {
            return new ScheduleLineResponse(
                    line.number(), line.contractualDueDate(), line.dueDate(), line.openingBalance(), line.principalDue(),
                    line.interestDue(), line.instalmentAmount(), line.closingBalance(), line.principalPaid(),
                    line.interestPaid(), line.penaltyAccrued(), line.status());
        }
    }

    public record ScheduleResponse(
            UUID loanAccountId,
            int version,
            String status,
            String engineVersion,
            String servicingVersion,
            String fingerprint,
            String parameters,
            String reason,
            List<ScheduleLineResponse> lines
    ) {
        public static ScheduleResponse from(ScheduleView view) {
            return new ScheduleResponse(
                    view.account().id(), view.schedule().versionNumber(), view.schedule().status(),
                    view.schedule().engineVersion(), view.schedule().servicingVersion(), view.schedule().fingerprint(),
                    view.schedule().parameters(), view.schedule().reason(),
                    view.lines().stream().map(ScheduleLineResponse::from).toList());
        }
    }

    public record StatementResponse(
            UUID loanAccountId,
            LocalDate from,
            LocalDate to,
            String currencyCode,
            BalanceResponse opening,
            List<BalanceResponse> movements,
            List<EventResponse> events,
            List<RepaymentResponse> repayments,
            BigDecimal contractualInterest,
            BigDecimal interestPaid,
            BigDecimal unearnedInterest
    ) {
        public static StatementResponse from(StatementView view) {
            return new StatementResponse(
                    view.account().id(), view.from(), view.to(), view.account().currencyCode(),
                    snapshot(view.account(), view.opening()),
                    view.movements().stream().map(movement -> snapshot(view.account(), movement)).toList(),
                    view.events().stream().map(EventResponse::from).toList(),
                    view.repayments().stream().map(repayment -> RepaymentResponse.from(repayment, List.of())).toList(),
                    view.contractualInterest(), view.interestPaid(), view.unearnedInterest());
        }

        private static BalanceResponse snapshot(LoanAccount account, BalanceSnapshot snapshot) {
            if (snapshot == null) {
                return null;
            }
            BigDecimal total = snapshot.outstandingPrincipal().add(snapshot.outstandingInterest())
                    .add(snapshot.outstandingFees()).add(snapshot.outstandingPenalties());
            return new BalanceResponse(
                    snapshot.loanAccountId(), account.currencyCode(), account.status().name(), snapshot.outstandingPrincipal(),
                    snapshot.accruedInterest(), snapshot.outstandingInterest(), snapshot.outstandingFees(),
                    snapshot.outstandingPenalties(), snapshot.overpayment(), total, snapshot.valueDate());
        }
    }

    public record EventResponse(String eventType, String payload, Instant occurredAt) {
        static EventResponse from(AccountEvent event) {
            return new EventResponse(event.eventType(), event.payload(), event.occurredAt());
        }
    }

    public record AllocationResponse(String component, BigDecimal amount, UUID scheduleItemId) {
        static AllocationResponse from(RepaymentAllocation allocation) {
            return new AllocationResponse(allocation.component(), allocation.amount(), allocation.scheduleLineId());
        }
    }

    public record RepaymentResponse(
            UUID id,
            String reference,
            UUID loanAccountId,
            String channel,
            String status,
            BigDecimal amount,
            LocalDate valueDate,
            boolean settlement,
            BigDecimal feeCharged,
            String coreReference,
            String externalReference,
            int allocationPolicyVersion,
            String failureReason,
            List<AllocationResponse> allocations
    ) {
        public static RepaymentResponse from(LoanRepayment repayment, List<RepaymentAllocation> allocations) {
            return new RepaymentResponse(
                    repayment.id(), repayment.reference(), repayment.loanAccountId(), repayment.channel().name(),
                    repayment.status().name(), repayment.amount(), repayment.valueDate(), repayment.settlement(),
                    repayment.feeCharged(), repayment.coreReference(), repayment.externalReference(),
                    repayment.allocationPolicyVersion(), repayment.failureReason(),
                    allocations.stream().map(AllocationResponse::from).toList());
        }
    }

    public record SettlementQuoteResponse(
            UUID loanAccountId,
            LocalDate valueDate,
            String currencyCode,
            BigDecimal principal,
            BigDecimal accruedInterest,
            BigDecimal unearnedInterest,
            BigDecimal fees,
            BigDecimal penalties,
            BigDecimal earlyFee,
            BigDecimal interestCredit,
            BigDecimal totalDue
    ) {
        public static SettlementQuoteResponse from(LoanAccount account, LocalDate valueDate, SettlementQuote quote) {
            return new SettlementQuoteResponse(
                    account.id(), valueDate, account.currencyCode(), quote.principal(), quote.accruedInterest(),
                    quote.unearnedInterest(), quote.fees(), quote.penalties(), quote.earlyFee(), quote.interestCredit(), quote.totalDue());
        }
    }

    public record JournalLineResponse(int lineNumber, String accountCode, String direction, BigDecimal amount, String currencyCode, String narration) {
        static JournalLineResponse from(JournalLine line) {
            return new JournalLineResponse(line.lineNumber(), line.accountCode(), line.direction(), line.amount(), line.currencyCode(), line.narration());
        }
    }

    public record JournalResponse(
            UUID id,
            String reference,
            UUID loanAccountId,
            String eventCode,
            String currencyCode,
            String status,
            UUID reversalOf,
            boolean requiresApproval,
            UUID requestedBy,
            UUID approvedBy,
            Instant postedAt,
            List<JournalLineResponse> lines
    ) {
        public static JournalResponse from(JournalHeader header, List<JournalLine> lines) {
            return new JournalResponse(
                    header.id(), header.reference(), header.loanAccountId(), header.eventCode(), header.currencyCode(),
                    header.status().name(), header.reversalOf(), header.requiresApproval(), header.requestedBy(),
                    header.approvedBy(), header.postedAt(), lines.stream().map(JournalLineResponse::from).toList());
        }
    }

    public record ReconciliationResponse(
            UUID loanAccountId,
            String currencyCode,
            BigDecimal servicingCash,
            BigDecimal coreServicingCash,
            BigDecimal difference,
            boolean matched
    ) {
        public static ReconciliationResponse from(Reconciliation reconciliation) {
            return new ReconciliationResponse(
                    reconciliation.loanAccountId(), reconciliation.currencyCode(), reconciliation.servicingCash(),
                    reconciliation.coreServicingCash(), reconciliation.difference(), reconciliation.matched());
        }
    }
}
