package com.mfb.lending.module.collateral.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record InsuranceRequest(
        @NotBlank @Size(max = 255) String insurerName,
        @NotBlank @Size(max = 128) String policyNumber,
        @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal coverAmount,
        @NotNull LocalDate startDate,
        @NotNull LocalDate expiryDate
) {
}
