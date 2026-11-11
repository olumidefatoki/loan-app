package com.mfb.lending.module.collateral.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EncumbranceResponse(
        UUID id,
        UUID loanCollateralId,
        String source,
        String holderName,
        String referenceNumber,
        BigDecimal amount,
        String status,
        Instant notedAt,
        Instant releasedAt
) {
}
