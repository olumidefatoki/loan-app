package com.mfb.lending.module.customer.domain;

import java.time.Instant;
import java.util.UUID;

public record DocumentRecord(
        UUID id,
        UUID customerId,
        DocumentType type,
        String fileName,
        String contentType,
        long sizeBytes,
        String checksumSha256,
        String storageKey,
        Instant createdAt
) {
}
