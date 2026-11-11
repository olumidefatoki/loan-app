package com.mfb.lending.module.approval.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record MatrixVersion(
        UUID id,
        UUID matrixId,
        int versionNumber,
        MatrixStatus status,
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
        Instant createdAt,
        String createdBy
) {
}
