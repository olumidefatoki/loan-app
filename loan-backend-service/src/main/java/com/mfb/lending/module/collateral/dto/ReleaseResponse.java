package com.mfb.lending.module.collateral.dto;

import java.time.Instant;
import java.util.UUID;

public record ReleaseResponse(
        UUID id,
        String settlementReference,
        String dischargeReference,
        String reason,
        UUID releasedBy,
        Instant releasedAt
) {
}
