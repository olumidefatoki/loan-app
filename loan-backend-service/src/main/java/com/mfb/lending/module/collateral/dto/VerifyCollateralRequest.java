package com.mfb.lending.module.collateral.dto;

import java.math.BigDecimal;

import com.mfb.lending.module.collateral.domain.CheckOutcome;
import com.mfb.lending.module.collateral.domain.CollateralCheck;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record VerifyCollateralRequest(
        @NotNull CollateralCheck check,
        CheckOutcome outcome,
        @Size(max = 500) String notes,
        @Size(max = 255) String encumbranceHolder,
        @Size(max = 128) String encumbranceReference,
        @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal encumbranceAmount
) {
}
