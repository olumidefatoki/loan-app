package com.mfb.lending.module.approval.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record ApprovalDecisionRequest(
        @NotNull @PositiveOrZero Integer version,
        @NotBlank @Size(max = 500) String reason
) {
}
