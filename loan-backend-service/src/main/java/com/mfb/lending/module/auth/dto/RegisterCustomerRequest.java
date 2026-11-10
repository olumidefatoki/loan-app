package com.mfb.lending.module.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterCustomerRequest(
        @NotBlank @Pattern(regexp = "INDIVIDUAL|SME") String customerType,
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneNumber,
        @NotBlank @Size(min = 12, max = 128) String password,
        @Size(max = 255) String businessName
) {
}
