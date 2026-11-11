package com.mfb.lending.module.loanoffer.domain;

import java.time.Instant;
import java.util.UUID;

public record StoredAgreement(
        UUID id,
        UUID offerVersionId,
        AgreementKind kind,
        UUID templateId,
        int templateVersion,
        int documentVersion,
        String title,
        String fileName,
        String contentType,
        String storageKey,
        String checksumSha256,
        long sizeBytes,
        String renderedText,
        Instant supersededAt,
        Instant frozenAt,
        String createdBy
) {
}
