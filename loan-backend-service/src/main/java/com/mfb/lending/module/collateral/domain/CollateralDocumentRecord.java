package com.mfb.lending.module.collateral.domain;

import java.time.Instant;
import java.util.UUID;

public record CollateralDocumentRecord(
        UUID id,
        UUID collateralId,
        CollateralDocumentCode code,
        String fileName,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        String storageKey,
        Instant createdAt
) {
}
