package com.mfb.lending.module.approval.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ApprovalHistoryEntry(
        UUID id,
        UUID approvalId,
        UUID actorId,
        String actorRole,
        ApprovalStage stage,
        ApprovalDecision decision,
        BigDecimal amount,
        String reason,
        String correlationId,
        Instant occurredAt
) {
}
