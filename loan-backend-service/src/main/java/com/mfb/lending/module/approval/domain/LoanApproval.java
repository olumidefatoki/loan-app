package com.mfb.lending.module.approval.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LoanApproval(
        UUID id,
        String reference,
        UUID applicationId,
        int applicationRevision,
        UUID creditAssessmentId,
        UUID creditResultId,
        UUID matrixId,
        UUID matrixVersionId,
        UUID exceptionId,
        BigDecimal amount,
        String currencyCode,
        ApprovalStage stage,
        int stageSequence,
        ApprovalStatus status,
        String decision,
        String decisionReason,
        UUID decidedBy,
        Instant decidedAt,
        Instant expiresAt,
        boolean automated,
        UUID requestedBy,
        String correlationId,
        int version,
        Instant createdAt,
        Instant updatedAt
) {
}
