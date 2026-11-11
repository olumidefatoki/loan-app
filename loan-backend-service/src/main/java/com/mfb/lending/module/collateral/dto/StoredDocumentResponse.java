package com.mfb.lending.module.collateral.dto;

import java.time.Instant;
import java.util.UUID;

public record StoredDocumentResponse(
        UUID id,
        String documentCode,
        String fileName,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        Instant uploadedAt
) {
}
