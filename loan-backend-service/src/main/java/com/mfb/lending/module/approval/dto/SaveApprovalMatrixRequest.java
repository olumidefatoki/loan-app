package com.mfb.lending.module.approval.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SaveApprovalMatrixRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 2000) String description,
        UUID productId,
        String customerSegment,
        UUID branchId,
        String riskRating,
        String collateralStatus,
        String securityClassification,
        String exceptionType,
        @NotNull Boolean automatedAllowed,
        @NotNull BigDecimal amountMin,
        BigDecimal amountMax,
        @NotEmpty @Valid List<LevelRequest> levels
) {
}
