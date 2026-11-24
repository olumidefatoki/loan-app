package com.mfb.lending.module.collections.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.module.collections.application.CollectionCaseService.CaseDetail;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionActivity;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionAssignment;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionCase;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionEscalation;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionPromise;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionsReport;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanRestructuring;
import com.mfb.lending.module.collections.domain.CollectionModels.OfficerPerformance;
import com.mfb.lending.module.collections.domain.CollectionModels.ParBand;
import com.mfb.lending.module.collections.domain.CollectionModels.ParReport;
import com.mfb.lending.module.collections.domain.CollectionModels.RecoveryTransaction;
import com.mfb.lending.module.collections.domain.CollectionModels.RestructuringApproval;
import com.mfb.lending.module.collections.domain.CollectionModels.RestructuringScheduleVersion;

public final class CollectionResponses {

    private CollectionResponses() {
    }

    public record CaseResponse(
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
            String closureNote
    ) {
        public static CaseResponse from(CollectionCase collectionCase) {
            return new CaseResponse(
                    collectionCase.id(), collectionCase.reference(), collectionCase.loanAccountId(), collectionCase.customerId(),
                    collectionCase.branchId(), collectionCase.status(), collectionCase.bucket(), collectionCase.daysPastDue(),
                    collectionCase.overdueAmount(), collectionCase.collectionExposure(), collectionCase.missedInstalments(),
                    collectionCase.openedOn(), collectionCase.closedAt(), collectionCase.closureOutcome(), collectionCase.closureNote());
        }
    }

    public record AssignmentResponse(UUID id, UUID officerId, UUID branchId, UUID assignedBy, Instant assignedAt, boolean active) {
        public static AssignmentResponse from(CollectionAssignment assignment) {
            return new AssignmentResponse(assignment.id(), assignment.officerId(), assignment.branchId(), assignment.assignedBy(), assignment.assignedAt(), assignment.active());
        }
    }

    public record ActivityResponse(UUID id, String activityType, String channel, String summary, Instant contactedAt, UUID actorId) {
        public static ActivityResponse from(CollectionActivity activity) {
            return new ActivityResponse(activity.id(), activity.activityType(), activity.channel(), activity.summary(), activity.contactedAt(), activity.actorId());
        }
    }

    public record PromiseResponse(UUID id, BigDecimal amount, LocalDate promiseDate, String status, String note, Instant createdAt, Instant resolvedAt) {
        public static PromiseResponse from(CollectionPromise promise) {
            return new PromiseResponse(promise.id(), promise.amount(), promise.promiseDate(), promise.status(), promise.note(), promise.createdAt(), promise.resolvedAt());
        }
    }

    public record EscalationResponse(UUID id, String reason, String fromBucket, int level, String status, UUID requestedBy, Instant createdAt) {
        public static EscalationResponse from(CollectionEscalation escalation) {
            return new EscalationResponse(escalation.id(), escalation.reason(), escalation.fromBucket(), escalation.level(), escalation.status(), escalation.requestedBy(), escalation.createdAt());
        }
    }

    public record RecoveryResponse(UUID id, UUID repaymentId, BigDecimal amount, String outcome, String status, Instant recordedAt) {
        public static RecoveryResponse from(RecoveryTransaction recovery) {
            return new RecoveryResponse(recovery.id(), recovery.repaymentId(), recovery.amount(), recovery.outcome(), recovery.status(), recovery.recordedAt());
        }
    }

    public record CaseDetailResponse(
            CaseResponse collectionCase,
            List<AssignmentResponse> assignments,
            List<ActivityResponse> activities,
            List<PromiseResponse> promises,
            List<EscalationResponse> escalations,
            List<RecoveryResponse> recoveries
    ) {
        public static CaseDetailResponse from(CaseDetail detail) {
            return new CaseDetailResponse(
                    CaseResponse.from(detail.collectionCase()),
                    detail.assignments().stream().map(AssignmentResponse::from).toList(),
                    detail.activities().stream().map(ActivityResponse::from).toList(),
                    detail.promises().stream().map(PromiseResponse::from).toList(),
                    detail.escalations().stream().map(EscalationResponse::from).toList(),
                    detail.recoveries().stream().map(RecoveryResponse::from).toList());
        }
    }

    public record RestructuringResponse(
            UUID id,
            UUID loanAccountId,
            String type,
            String status,
            String justification,
            Integer tenure,
            BigDecimal interestRate,
            String repaymentFrequency,
            Integer moratoriumDays,
            BigDecimal partialSettlementAmount,
            BigDecimal revisedInstalment,
            LocalDate revisedMaturity,
            int daysPastDueAtRequest,
            String bucketAtRequest,
            BigDecimal overdueAtRequest,
            Integer daysPastDueAtDecision,
            String bucketAtDecision,
            BigDecimal overdueAtDecision,
            BigDecimal outstandingPrincipalBefore,
            BigDecimal outstandingInterestBefore,
            BigDecimal outstandingPrincipalAfter,
            BigDecimal outstandingInterestAfter,
            Boolean reconciliationMatched,
            BigDecimal reconciliationDifference,
            RestructuringApprovalResponse approval,
            ScheduleLinkResponse schedule
    ) {
        public static RestructuringResponse from(LoanRestructuring restructuring, RestructuringApproval approval, RestructuringScheduleVersion schedule) {
            return new RestructuringResponse(
                    restructuring.id(), restructuring.loanAccountId(), restructuring.type(), restructuring.status(), restructuring.justification(),
                    restructuring.tenure(), restructuring.interestRate(), restructuring.repaymentFrequency(), restructuring.moratoriumDays(),
                    restructuring.partialSettlementAmount(), restructuring.revisedInstalment(), restructuring.revisedMaturity(),
                    restructuring.daysPastDueAtRequest(), restructuring.bucketAtRequest(), restructuring.overdueAtRequest(),
                    restructuring.daysPastDueAtDecision(), restructuring.bucketAtDecision(), restructuring.overdueAtDecision(),
                    restructuring.outstandingPrincipalBefore(), restructuring.outstandingInterestBefore(),
                    restructuring.outstandingPrincipalAfter(), restructuring.outstandingInterestAfter(),
                    restructuring.reconciliationMatched(), restructuring.reconciliationDifference(),
                    approval == null ? null : RestructuringApprovalResponse.from(approval),
                    schedule == null ? null : ScheduleLinkResponse.from(schedule));
        }
    }

    public record RestructuringApprovalResponse(String decision, UUID approverId, String reason, Instant decidedAt) {
        public static RestructuringApprovalResponse from(RestructuringApproval approval) {
            return new RestructuringApprovalResponse(approval.decision(), approval.approverId(), approval.reason(), approval.decidedAt());
        }
    }

    public record ScheduleLinkResponse(UUID previousScheduleId, UUID newScheduleId, int previousVersion, int newVersion, boolean preserved) {
        public static ScheduleLinkResponse from(RestructuringScheduleVersion link) {
            return new ScheduleLinkResponse(link.previousScheduleId(), link.newScheduleId(), link.previousVersion(), link.newVersion(), link.preserved());
        }
    }

    public record ParBandResponse(int thresholdDays, int accounts, BigDecimal outstandingPrincipal, BigDecimal ratio) {
        public static ParBandResponse from(ParBand band) {
            return new ParBandResponse(band.thresholdDays(), band.accounts(), band.outstandingPrincipal(), band.ratio());
        }
    }

    public record ParResponse(
            LocalDate asOfDate,
            String denominatorRule,
            String numeratorRule,
            String daysPastDueRule,
            String confirmedAllocationRule,
            String collectionExposureRule,
            BigDecimal denominatorOutstandingPrincipal,
            int accountsInDenominator,
            ParBandResponse par30,
            ParBandResponse par60,
            ParBandResponse par90
    ) {
        public static ParResponse from(ParReport report) {
            return new ParResponse(
                    report.asOfDate(), report.denominatorRule(), report.numeratorRule(), report.daysPastDueRule(),
                    report.confirmedAllocationRule(), report.collectionExposureRule(), report.denominatorOutstandingPrincipal(),
                    report.accountsInDenominator(), ParBandResponse.from(report.par30()), ParBandResponse.from(report.par60()),
                    ParBandResponse.from(report.par90()));
        }
    }

    public record OfficerPerformanceResponse(UUID officerId, long activeCases, BigDecimal recoveredAmount) {
        public static OfficerPerformanceResponse from(OfficerPerformance officer) {
            return new OfficerPerformanceResponse(officer.officerId(), officer.activeCases(), officer.recoveredAmount());
        }
    }

    public record CollectionsReportResponse(
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
            List<OfficerPerformanceResponse> officers
    ) {
        public static CollectionsReportResponse from(CollectionsReport report) {
            return new CollectionsReportResponse(
                    report.asOfDate(), report.openCases(), report.closedCases(), report.escalatedCases(), report.promisesOpen(),
                    report.promisesKept(), report.promisesBroken(), report.recoveredAmount(), report.collectionExposure(),
                    report.collectionExposureRule(), report.officers().stream().map(OfficerPerformanceResponse::from).toList());
        }
    }
}
