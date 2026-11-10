package com.mfb.lending.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyOtpRequest(
        @NotBlank @Size(max = 255) String identifier,
        @NotBlank @Pattern(regexp = "EMAIL_VERIFICATION|PHONE_VERIFICATION|PASSWORD_RESET") String purpose,
        @NotBlank @Pattern(regexp = "\\d{6,8}") String code
) {
}
