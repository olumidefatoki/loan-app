package com.mfb.lending.module.collateral.domain;

import java.time.Instant;
import java.util.UUID;

public record ReleaseRecord(
        UUID id,
        UUID collateralId,
        String settlementReference,
        String dischargeReference,
        String reason,
        UUID releasedBy,
        Instant releasedAt
) {
}
