package com.mfb.lending.module.approval.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MatrixVersionResponse(
        UUID id,
        int versionNumber,
        String status,
        UUID productId,
        String customerSegment,
        UUID branchId,
        String riskRating,
        String collateralStatus,
        String securityClassification,
        String exceptionType,
        boolean automatedAllowed,
        BigDecimal amountMin,
        BigDecimal amountMax,
        String activatedBy,
        Instant activatedAt,
        String createdBy,
        List<ApprovalLevelResponse> levels
) {
}
