package com.mfb.lending.module.loanoffer.domain;

import java.time.Instant;
import java.util.UUID;

public record AgreementTemplate(
        UUID id,
        String code,
        String name,
        AgreementKind kind,
        int versionNumber,
        String body,
        boolean active,
        String checksumSha256,
        Instant createdAt,
        String createdBy
) {
}
