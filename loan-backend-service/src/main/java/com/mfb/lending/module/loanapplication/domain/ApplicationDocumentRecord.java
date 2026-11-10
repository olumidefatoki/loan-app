package com.mfb.lending.module.loanapplication.domain;

import java.time.Instant;
import java.util.UUID;

public record ApplicationDocumentRecord(
        UUID id,
        UUID applicationId,
        String documentCode,
        String documentName,
        String fileName,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        String storageKey,
        Instant uploadedAt
) {
}
