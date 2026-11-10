package com.mfb.lending.module.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record UserRecord(
        UUID id,
        PrincipalType principalType,
        CustomerType customerType,
        String fullName,
        String businessName,
        String email,
        String phoneNumber,
        String passwordHash,
        UserStatus status,
        boolean emailVerified,
        boolean phoneVerified,
        int failedLoginCount,
        Instant lockedUntil,
        Instant passwordChangedAt,
        Instant passwordExpiresAt,
        boolean mfaEnabled,
        String mfaSecretEncrypted,
        Long mfaLastAcceptedStep,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
