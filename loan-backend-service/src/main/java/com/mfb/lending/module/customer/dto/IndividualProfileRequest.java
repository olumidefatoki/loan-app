package com.mfb.lending.module.customer.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record IndividualProfileRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotNull LocalDate dateOfBirth,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneNumber,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Pattern(regexp = "\\d{11}") String bvn,
        @NotBlank @Pattern(regexp = "\\d{11}") String nin,
        @NotNull @Valid AddressRequest residentialAddress,
        @NotBlank @Pattern(regexp = "EMPLOYED|SELF_EMPLOYED|UNEMPLOYED|STUDENT|RETIRED") String employmentStatus,
        @Size(max = 255) String employerName,
        @Size(max = 255) String occupation,
        @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal monthlyIncome,
        @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal monthlyExpenses,
        @NotNull @Valid NextOfKinRequest nextOfKin,
        @Valid List<ConsentRequest> consents
) {
}
