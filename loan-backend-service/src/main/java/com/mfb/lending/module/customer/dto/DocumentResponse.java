package com.mfb.lending.module.customer.dto;

import java.time.Instant;
import java.util.UUID;

public record DocumentResponse(
        UUID id,
        String documentType,
        String fileName,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        String malwareScanStatus,
        Instant createdAt
) {
}
