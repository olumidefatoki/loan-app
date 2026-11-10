package com.mfb.lending.module.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record OtpRecord(
        UUID id,
        UUID userId,
        OtpPurpose purpose,
        OtpChannel channel,
        String destination,
        String codeHash,
        Instant expiresAt,
        int attemptCount,
        int maxAttempts,
        Instant consumedAt,
        Instant createdAt
) {
}
