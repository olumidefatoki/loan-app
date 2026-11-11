package com.mfb.lending.module.approval.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record DelegateApprovalRequest(
        @NotNull @PositiveOrZero Integer version,
        @NotNull UUID delegateUserId,
        @NotBlank @Size(max = 500) String reason
) {
}
