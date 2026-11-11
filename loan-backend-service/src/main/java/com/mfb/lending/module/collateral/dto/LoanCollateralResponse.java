package com.mfb.lending.module.collateral.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record LoanCollateralResponse(
        UUID id,
        UUID applicationId,
        UUID collateralId,
        String collateralReference,
        String collateralType,
        BigDecimal allocatedAmount,
        boolean sharedSecurity,
        String status,
        BigDecimal eligibleLendingValue,
        BigDecimal availableAmount,
        BigDecimal coverageRatio,
        String collateralStatus
) {
}
