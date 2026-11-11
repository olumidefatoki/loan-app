package com.mfb.lending.module.integration.corebanking.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record RepaymentInstructionRequest(
        @NotNull UUID applicationId,
        @NotBlank String idempotencyKey,
        @NotNull BigDecimal amount
) {
}
