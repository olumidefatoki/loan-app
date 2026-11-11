package com.mfb.lending.module.integration.corebanking.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record DisburseInstructionRequest(
        @NotNull UUID applicationId,
        @NotBlank String idempotencyKey,
        BigDecimal amount,
        UUID accountId
) {
}
