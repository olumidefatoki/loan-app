package com.mfb.lending.module.customer.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DirectorRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotNull LocalDate dateOfBirth,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneNumber,
        @Email @Size(max = 255) String email,
        @Size(max = 128) String position,
        @NotBlank @Pattern(regexp = "\\d{11}") String bvn,
        @Pattern(regexp = "\\d{11}") String nin
) {
}
