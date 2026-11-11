package com.mfb.lending.module.loanoffer.dto;

import java.time.Instant;
import java.util.UUID;

public record AgreementTemplateResponse(
        UUID id,
        String code,
        String name,
        String kind,
        int versionNumber,
        String body,
        boolean active,
        String checksumSha256,
        Instant createdAt
) {
}
