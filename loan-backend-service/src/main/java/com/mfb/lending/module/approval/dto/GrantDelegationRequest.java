package com.mfb.lending.module.approval.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GrantDelegationRequest(
        @NotNull UUID userId,
        @NotBlank String roleCode,
        @NotBlank String stage,
        UUID branchId,
        @NotNull BigDecimal maxAmount,
        @NotNull Instant validFrom,
        Instant validUntil,
        @NotBlank @Size(max = 500) String reason
) {
}
