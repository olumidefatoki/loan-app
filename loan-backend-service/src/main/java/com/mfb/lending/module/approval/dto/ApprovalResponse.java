package com.mfb.lending.module.approval.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ApprovalResponse(
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
        String stage,
        int stageSequence,
        String status,
        String decision,
        String decisionReason,
        boolean automated,
        UUID requestedBy,
        UUID decidedBy,
        Instant decidedAt,
        Instant expiresAt,
        int version,
        Instant createdAt
) {
}
