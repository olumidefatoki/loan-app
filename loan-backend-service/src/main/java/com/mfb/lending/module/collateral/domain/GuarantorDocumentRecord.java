package com.mfb.lending.module.collateral.domain;

import java.time.Instant;
import java.util.UUID;

public record GuarantorDocumentRecord(
        UUID id,
        UUID guarantorId,
        GuarantorDocumentCode code,
        String fileName,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        String storageKey,
        Instant createdAt
) {
}
