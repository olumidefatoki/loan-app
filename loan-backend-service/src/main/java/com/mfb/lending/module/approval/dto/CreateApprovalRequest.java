package com.mfb.lending.module.approval.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateApprovalRequest(
        @NotNull UUID applicationId,
        @Size(max = 40) String exceptionType,
        @Size(max = 500) String reason
) {
}
