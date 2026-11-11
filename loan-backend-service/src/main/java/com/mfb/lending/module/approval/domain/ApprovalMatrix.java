package com.mfb.lending.module.approval.domain;

import java.time.Instant;
import java.util.UUID;

public record ApprovalMatrix(
        UUID id,
        String code,
        String name,
        String description,
        MatrixStatus status,
        UUID currentVersionId,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
