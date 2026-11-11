package com.mfb.lending.module.collateral.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.mfb.lending.module.collateral.domain.CollateralType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateCollateralRequest(
        @NotNull UUID customerId,
        @NotNull CollateralType collateralType,
        @NotBlank @Size(max = 1000) String description,
        @NotBlank @Size(max = 255) String ownerName,
        @Size(max = 128) String ownerIdentifier,
        @Size(max = 128) String identificationNumber,
        @Size(max = 500) String location,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal marketValue,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal forcedSaleValue,
        @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal eligibleLendingValue,
        @NotNull LocalDate valuationDate,
        LocalDate expiryDate,
        @NotBlank @Size(max = 255) String valuerName,
        @Size(max = 128) String valuerReference,
        @DecimalMin("0.000001") @DecimalMax("1") @Digits(integer = 1, fraction = 6) BigDecimal lendingRatio,
        @Valid InsuranceRequest insurance
) {
}
