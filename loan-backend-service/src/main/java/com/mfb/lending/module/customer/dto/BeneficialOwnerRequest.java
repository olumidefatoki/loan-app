package com.mfb.lending.module.customer.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record BeneficialOwnerRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotNull LocalDate dateOfBirth,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneNumber,
        @Email @Size(max = 255) String email,
        @NotNull @DecimalMin(value = "0.01") @DecimalMax(value = "100.00") @Digits(integer = 3, fraction = 2) BigDecimal ownershipPercentage,
        @NotBlank @Pattern(regexp = "\\d{11}") String bvn,
        @Pattern(regexp = "\\d{11}") String nin
) {
}
