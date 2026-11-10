package com.mfb.lending.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotBlank @Size(max = 255) String identifier,
        @NotBlank @Pattern(regexp = "\\d{6,8}") String code,
        @NotBlank @Size(min = 12, max = 128) String newPassword
) {
}
