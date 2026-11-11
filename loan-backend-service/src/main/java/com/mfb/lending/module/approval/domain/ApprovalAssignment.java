package com.mfb.lending.module.approval.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ApprovalAssignment(
        UUID id,
        UUID approvalId,
        ApprovalStage stage,
        UUID assigneeId,
        UUID assignedBy,
        String status,
        BigDecimal delegatedLimit,
        Instant createdAt
) {
}
