package com.mfb.lending.module.loanapplication.dto;

import java.time.Instant;
import java.util.UUID;

public record ApplicationDocumentResponse(
        UUID id,
        UUID applicationId,
        String documentCode,
        String documentName,
        String fileName,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        Instant uploadedAt
) {
}
