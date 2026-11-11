package com.mfb.lending.module.approval.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record LevelRequest(
        @NotBlank String stage,
        @NotNull Integer sequence,
        @NotBlank String roleCode,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        @NotNull Integer quorum,
        @NotNull Integer expiryHours,
        @NotNull Boolean manualRequired,
        @NotNull Boolean automated,
        @NotNull Boolean escalateOnExpiry
) {
}
