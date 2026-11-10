package com.mfb.lending.module.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshTokenRecord(
        UUID id,
        UUID sessionId,
        UUID userId,
        UUID familyId,
        String tokenHash,
        Instant expiresAt,
        Instant createdAt,
        Instant revokedAt,
        UUID replacedBy
) {
}
