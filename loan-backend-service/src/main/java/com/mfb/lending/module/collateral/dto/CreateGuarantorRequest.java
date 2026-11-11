package com.mfb.lending.module.collateral.dto;

import java.math.BigDecimal;

import com.mfb.lending.module.collateral.domain.GuarantorEmployment;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateGuarantorRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank String phoneNumber,
        @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 500) String addressLine,
        @NotBlank String bvn,
        @NotBlank String nin,
        @NotBlank @Size(max = 128) String bvnVerificationReference,
        @NotBlank @Size(max = 128) String ninVerificationReference,
        @NotNull GuarantorEmployment employmentStatus,
        @Size(max = 255) String employerName,
        @Size(max = 160) String jobTitle,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal monthlyIncome,
        @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal monthlyObligations,
        @NotNull @AssertTrue(message = "Guarantor consent must be recorded") Boolean consentGiven,
        @NotBlank @Size(max = 2000) String consentStatement
) {
}
