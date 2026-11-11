package com.mfb.lending.module.collateral.dto;

import java.math.BigDecimal;
import java.util.List;

public record LoanCollateralListResponse(
        BigDecimal facilityAmount,
        BigDecimal totalAllocated,
        BigDecimal coverageRatio,
        List<LoanCollateralResponse> items
) {
}
