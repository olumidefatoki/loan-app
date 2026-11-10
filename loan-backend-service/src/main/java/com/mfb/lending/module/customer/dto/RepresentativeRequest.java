package com.mfb.lending.module.customer.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RepresentativeRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneNumber,
        @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 128) String position
) {
}
