package com.mfb.lending.module.collateral.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record EncumbranceRecord(
        UUID id,
        UUID collateralId,
        UUID loanCollateralId,
        EncumbranceSource source,
        String holderName,
        String referenceNumber,
        BigDecimal amount,
        PledgeStatus status,
        Instant notedAt,
        Instant releasedAt,
        UUID createdBy
) {
}
