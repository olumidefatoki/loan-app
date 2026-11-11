package com.mfb.lending.module.credit.domain;

import java.time.Instant;
import java.util.UUID;

public record ScoringModelRecord(
        UUID id,
        String code,
        String name,
        ModelSegment segment,
        UUID productId,
        ModelStatus status,
        UUID currentVersionId,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
