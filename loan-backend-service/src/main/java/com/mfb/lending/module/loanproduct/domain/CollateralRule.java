package com.mfb.lending.module.loanproduct.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record CollateralRule(
        UUID id,
        UUID versionId,
        String collateralType,
        String description,
        boolean mandatory,
        BigDecimal minimumCoveragePercent
) {
}
