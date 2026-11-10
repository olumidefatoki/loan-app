package com.mfb.lending.module.loanproduct.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Loan product and the version currently in force, plus any unpublished version.")
public record LoanProductResponse(
        UUID id,
        String productCode,
        String category,
        String status,
        LoanProductVersionResponse currentVersion,
        UUID pendingVersionId,
        String pendingVersionStatus,
        Instant createdAt,
        Instant updatedAt
) {
}
