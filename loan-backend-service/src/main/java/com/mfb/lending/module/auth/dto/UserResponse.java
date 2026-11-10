package com.mfb.lending.module.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserResponse(
        UUID id,
        String principalType,
        String customerType,
        String fullName,
        String businessName,
        String email,
        String phoneNumber,
        String status,
        boolean emailVerified,
        boolean phoneVerified,
        boolean mfaEnabled,
        List<String> roles,
        List<String> permissions,
        List<UUID> branchIds,
        Instant passwordExpiresAt
) {
}
