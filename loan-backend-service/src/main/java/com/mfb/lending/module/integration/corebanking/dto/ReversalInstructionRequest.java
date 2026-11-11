package com.mfb.lending.module.integration.corebanking.dto;

import jakarta.validation.constraints.NotBlank;

public record ReversalInstructionRequest(@NotBlank String idempotencyKey) {
}
