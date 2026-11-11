package com.mfb.lending.module.collateral.dto;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

public record AllocateCollateralRequest(
        @NotNull UUID collateralId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal allocatedAmount,
        @NotNull Boolean sharedSecurity
) {
}
