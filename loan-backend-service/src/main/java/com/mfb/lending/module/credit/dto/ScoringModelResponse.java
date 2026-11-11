package com.mfb.lending.module.credit.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScoringModelResponse(
        UUID id,
        String code,
        String name,
        String segment,
        UUID productId,
        String status,
        ScoringVersionResponse currentVersion,
        List<ScoringVersionResponse> versions
) {
}
