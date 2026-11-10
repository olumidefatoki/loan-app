package com.mfb.lending.module.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record SessionRecord(
        UUID id,
        UUID userId,
        String status,
        String ipAddress,
        String userAgent,
        Instant createdAt,
        Instant lastSeenAt,
        Instant expiresAt,
        Instant revokedAt,
        String revokedReason
) {

    public boolean active(Instant now) {
        return "ACTIVE".equals(status) && revokedAt == null && expiresAt.isAfter(now);
    }
}
