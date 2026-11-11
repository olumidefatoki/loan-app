package com.mfb.lending.module.approval.application;

import java.util.List;

import com.mfb.lending.module.approval.domain.ApprovalDelegation;
import com.mfb.lending.module.approval.domain.ApprovalHistoryEntry;
import com.mfb.lending.module.approval.domain.ApprovalLevel;
import com.mfb.lending.module.approval.domain.ApprovalMatrix;
import com.mfb.lending.module.approval.domain.LoanApproval;
import com.mfb.lending.module.approval.domain.MatrixVersion;
import com.mfb.lending.module.approval.dto.ApprovalHistoryResponse;
import com.mfb.lending.module.approval.dto.ApprovalLevelResponse;
import com.mfb.lending.module.approval.dto.ApprovalMatrixResponse;
import com.mfb.lending.module.approval.dto.ApprovalResponse;
import com.mfb.lending.module.approval.dto.DelegationResponse;
import com.mfb.lending.module.approval.dto.MatrixVersionResponse;
import org.springframework.stereotype.Component;

@Component
public class ApprovalViews {

    public ApprovalResponse approval(LoanApproval approval) {
        return new ApprovalResponse(
                approval.id(),
                approval.reference(),
                approval.applicationId(),
                approval.applicationRevision(),
                approval.creditAssessmentId(),
                approval.creditResultId(),
                approval.matrixId(),
                approval.matrixVersionId(),
                approval.exceptionId(),
                approval.amount(),
                approval.currencyCode(),
                approval.stage().name(),
                approval.stageSequence(),
                approval.status().name(),
                approval.decision(),
                approval.decisionReason(),
                approval.automated(),
                approval.requestedBy(),
                approval.decidedBy(),
                approval.decidedAt(),
                approval.expiresAt(),
                approval.version(),
                approval.createdAt());
    }

    public ApprovalHistoryResponse history(ApprovalHistoryEntry entry) {
        return new ApprovalHistoryResponse(
                entry.id(),
                entry.actorId(),
                entry.actorRole(),
                entry.stage().name(),
                entry.decision().name(),
                entry.amount(),
                entry.reason(),
                entry.correlationId(),
                entry.occurredAt());
    }

    public DelegationResponse delegation(ApprovalDelegation delegation) {
        return new DelegationResponse(
                delegation.id(),
                delegation.userId(),
                delegation.approvalId(),
                delegation.roleCode(),
                delegation.stage().name(),
                delegation.branchId(),
                delegation.maxAmount(),
                delegation.currencyCode(),
                delegation.validFrom(),
                delegation.validUntil(),
                delegation.active(),
                delegation.reason(),
                delegation.grantedBy(),
                delegation.createdAt());
    }

    public ApprovalMatrixResponse matrix(ApprovalMatrix matrix, List<MatrixVersionResponse> versions) {
        return new ApprovalMatrixResponse(
                matrix.id(),
                matrix.code(),
                matrix.name(),
                matrix.description(),
                matrix.status().name(),
                matrix.createdAt(),
                matrix.updatedAt(),
                matrix.createdBy(),
                matrix.updatedBy(),
                versions);
    }

    public MatrixVersionResponse version(MatrixVersion version, List<ApprovalLevel> levels) {
        return new MatrixVersionResponse(
                version.id(),
                version.versionNumber(),
                version.status().name(),
                version.productId(),
                version.customerSegment(),
                version.branchId(),
                version.riskRating(),
                version.collateralStatus(),
                version.securityClassification(),
                version.exceptionType(),
                version.automatedAllowed(),
                version.amountMin(),
                version.amountMax(),
                version.activatedBy(),
                version.activatedAt(),
                version.createdBy(),
                levels.stream().map(this::level).toList());
    }

    private ApprovalLevelResponse level(ApprovalLevel level) {
        return new ApprovalLevelResponse(
                level.id(),
                level.stage().name(),
                level.sequence(),
                level.roleCode(),
                level.minAmount(),
                level.maxAmount(),
                level.quorum(),
                level.expiryHours(),
                level.manualRequired(),
                level.automated(),
                level.escalateOnExpiry());
    }
}
