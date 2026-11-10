package com.mfb.lending.module.loanproduct.domain;

import java.time.Instant;
import java.util.UUID;

public record LoanProductRecord(
        UUID id,
        String productCode,
        ProductCategory category,
        ProductStatus status,
        UUID currentVersionId,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
