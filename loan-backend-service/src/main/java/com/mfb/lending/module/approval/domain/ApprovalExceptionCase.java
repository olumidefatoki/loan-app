package com.mfb.lending.module.approval.domain;

import java.time.Instant;
import java.util.UUID;

public record ApprovalExceptionCase(
        UUID id,
        UUID approvalId,
        UUID applicationId,
        String exceptionType,
        String reason,
        String status,
        UUID requestedBy,
        UUID decidedBy,
        Instant decidedAt,
        Instant createdAt
) {
}
