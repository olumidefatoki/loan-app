package com.mfb.lending.module.collections.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class CollectionModels {

    private CollectionModels() {
    }

    public record BucketBand(String code, int minDays, Integer maxDays) {

        public static List<BucketBand> defaults() {
            return List.of(
                    new BucketBand("CURRENT", 0, 0),
                    new BucketBand("DPD_1_30", 1, 30),
                    new BucketBand("DPD_31_60", 31, 60),
                    new BucketBand("DPD_61_90", 61, 90),
                    new BucketBand("DPD_ABOVE_90", 91, null));
        }
    }

    public record InstalmentFact(
            UUID id,
            int number,
            LocalDate dueDate,
            BigDecimal principalDue,
            BigDecimal interestDue,
            BigDecimal principalConfirmed,
            BigDecimal interestConfirmed
    ) {
    }

    public record PostedRepayment(UUID id, BigDecimal amount, LocalDate valueDate) {
    }

    public record LoanPosition(
            UUID loanAccountId,
            String reference,
            UUID customerId,
            UUID branchId,
            String status,
            UUID scheduleId,
            int scheduleVersion,
            String repaymentFrequency,
            BigDecimal interestRate,
            Integer repaymentDayOfMonth,
            String holidayRule,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal outstandingFees,
            BigDecimal outstandingPenalties,
            List<InstalmentFact> instalments,
            List<PostedRepayment> postedRepayments
    ) {

        public boolean portfolioIncluded() {
            return ("ACTIVE".equals(status) || "OVERDUE".equals(status)) && outstandingPrincipal.signum() > 0;
        }
    }

    public record DelinquencyAssessment(
            int daysPastDue,
            String bucket,
            BigDecimal overdueAmount,
            BigDecimal outstandingPrincipal,
            BigDecimal overdueInterest,
            int missedInstalments,
            BigDecimal collectionExposure,
            boolean approachingDue,
            boolean partialPayment,
            boolean overdue,
            boolean delinquent,
            boolean escalationRequired,
            boolean portfolioIncluded
    ) {
    }

    public record DelinquencySnapshot(
            UUID id,
            UUID loanAccountId,
            LocalDate asOfDate,
            UUID scheduleId,
            int daysPastDue,
            String bucket,
            BigDecimal overdueAmount,
            BigDecimal outstandingPrincipal,
            BigDecimal overdueInterest,
            BigDecimal outstandingInterest,
            BigDecimal outstandingFees,
            BigDecimal outstandingPenalties,
            int missedInstalments,
            BigDecimal collectionExposure,
            boolean approachingDue,
            boolean partialPayment,
            boolean overdue,
            boolean delinquent,
            boolean escalationRequired,
            boolean portfolioIncluded,
            Instant calculatedAt
    ) {

        public DelinquencySnapshot withId(UUID existingId) {
            return new DelinquencySnapshot(
                    existingId, loanAccountId, asOfDate, scheduleId, daysPastDue, bucket, overdueAmount, outstandingPrincipal,
                    overdueInterest, outstandingInterest, outstandingFees, outstandingPenalties, missedInstalments, collectionExposure,
                    approachingDue, partialPayment, overdue, delinquent, escalationRequired, portfolioIncluded, calculatedAt);
        }
    }

    public record ParBand(int thresholdDays, int accounts, BigDecimal outstandingPrincipal, BigDecimal ratio) {
    }

    public record ParReport(
            LocalDate asOfDate,
            String denominatorRule,
            String numeratorRule,
            String daysPastDueRule,
            String confirmedAllocationRule,
            String collectionExposureRule,
            BigDecimal denominatorOutstandingPrincipal,
            int accountsInDenominator,
            ParBand par30,
            ParBand par60,
            ParBand par90
    ) {
    }

    public record CollectionCase(
            UUID id,
            String reference,
            UUID loanAccountId,
            UUID customerId,
            UUID branchId,
            String status,
            String bucket,
            int daysPastDue,
            BigDecimal overdueAmount,
            BigDecimal collectionExposure,
            int missedInstalments,
            LocalDate openedOn,
            Instant closedAt,
            String closureOutcome,
            String closureNote,
            int version,
            Instant createdAt,
            Instant updatedAt
    ) {

        public CollectionCase refresh(DelinquencySnapshot snapshot, Instant at) {
            return new CollectionCase(
                    id, reference, loanAccountId, customerId, branchId, status, snapshot.bucket(), snapshot.daysPastDue(),
                    snapshot.overdueAmount(), snapshot.collectionExposure(), snapshot.missedInstalments(), openedOn, closedAt,
                    closureOutcome, closureNote, version + 1, createdAt, at);
        }

        public CollectionCase assigned(UUID nextBranchId, String nextStatus, Instant at) {
            return new CollectionCase(
                    id, reference, loanAccountId, customerId, nextBranchId, nextStatus, bucket, daysPastDue, overdueAmount,
                    collectionExposure, missedInstalments, openedOn, closedAt, closureOutcome, closureNote, version + 1, createdAt, at);
        }

        public CollectionCase withStatus(String nextStatus, Instant at) {
            return new CollectionCase(
                    id, reference, loanAccountId, customerId, branchId, nextStatus, bucket, daysPastDue, overdueAmount,
                    collectionExposure, missedInstalments, openedOn, closedAt, closureOutcome, closureNote, version + 1, createdAt, at);
        }

        public CollectionCase closed(String outcome, String note, Instant at) {
            return new CollectionCase(
                    id, reference, loanAccountId, customerId, branchId, "CLOSED", bucket, daysPastDue, overdueAmount,
                    collectionExposure, missedInstalments, openedOn, at, outcome, note, version + 1, createdAt, at);
        }
    }

    public record CollectionAssignment(
            UUID id,
            UUID caseId,
            UUID officerId,
            UUID branchId,
            UUID assignedBy,
            Instant assignedAt,
            boolean active
    ) {
    }

    public record CollectionActivity(
            UUID id,
            UUID caseId,
            String activityType,
            String channel,
            String summary,
            Instant contactedAt,
            UUID actorId,
            Instant createdAt
    ) {
    }

    public record CollectionPromise(
            UUID id,
            UUID caseId,
            UUID loanAccountId,
            BigDecimal amount,
            LocalDate promiseDate,
            String status,
            String note,
            UUID createdBy,
            Instant createdAt,
            Instant resolvedAt
    ) {

        public CollectionPromise resolved(String nextStatus, Instant at) {
            return new CollectionPromise(id, caseId, loanAccountId, amount, promiseDate, nextStatus, note, createdBy, createdAt, at);
        }
    }

    public record CollectionEscalation(
            UUID id,
            UUID caseId,
            String reason,
            String fromBucket,
            int level,
            String status,
            UUID requestedBy,
            Instant createdAt
    ) {

        public CollectionEscalation closed() {
            return new CollectionEscalation(id, caseId, reason, fromBucket, level, "CLOSED", requestedBy, createdAt);
        }
    }

    public record LoanRestructuring(
            UUID id,
            UUID loanAccountId,
            String type,
            String status,
            String justification,
            Integer tenure,
            BigDecimal interestRate,
            String repaymentFrequency,
            Integer repaymentDayOfMonth,
            String holidayRule,
            BigDecimal revisedInstalment,
            Integer moratoriumDays,
            BigDecimal partialSettlementAmount,
            LocalDate revisedMaturity,
            LocalDate valueDate,
            UUID requestedBy,
            String requestedByName,
            Instant requestedAt,
            int daysPastDueAtRequest,
            String bucketAtRequest,
            BigDecimal overdueAtRequest,
            Integer daysPastDueAtDecision,
            String bucketAtDecision,
            BigDecimal overdueAtDecision,
            UUID previousScheduleId,
            UUID newScheduleId,
            BigDecimal outstandingPrincipalBefore,
            BigDecimal outstandingInterestBefore,
            BigDecimal outstandingPrincipalAfter,
            BigDecimal outstandingInterestAfter,
            Boolean reconciliationMatched,
            BigDecimal reconciliationDifference,
            int version
    ) {
    }

    public record RestructuringApproval(
            UUID id,
            UUID restructuringId,
            String decision,
            UUID approverId,
            String approverName,
            String reason,
            Instant decidedAt
    ) {
    }

    public record RestructuringScheduleVersion(
            UUID id,
            UUID restructuringId,
            UUID previousScheduleId,
            UUID newScheduleId,
            int previousVersion,
            int newVersion,
            boolean preserved
    ) {
    }

    public record RecoveryTransaction(
            UUID id,
            UUID caseId,
            UUID loanAccountId,
            UUID repaymentId,
            BigDecimal amount,
            String outcome,
            String status,
            String note,
            UUID recordedBy,
            Instant recordedAt
    ) {
    }

    public record CollectionNotification(
            UUID id,
            UUID loanAccountId,
            UUID customerId,
            String templateCode,
            LocalDate businessDate,
            UUID scheduleItemId,
            UUID repaymentId,
            UUID restructuringId,
            String payload,
            String status,
            Instant createdAt,
            Instant sentAt
    ) {
    }

    public record MessageTemplate(String code, String body, boolean active) {
    }

    public record OfficerPerformance(UUID officerId, long activeCases, BigDecimal recoveredAmount) {
    }

    public record CollectionsReport(
            LocalDate asOfDate,
            long openCases,
            long closedCases,
            long escalatedCases,
            long promisesOpen,
            long promisesKept,
            long promisesBroken,
            BigDecimal recoveredAmount,
            BigDecimal collectionExposure,
            String collectionExposureRule,
            List<OfficerPerformance> officers
    ) {
    }
}
