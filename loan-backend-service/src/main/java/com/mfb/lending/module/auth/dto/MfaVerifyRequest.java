package com.mfb.lending.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record MfaVerifyRequest(
        @NotBlank @Size(max = 4000) String mfaToken,
        @NotBlank @Pattern(regexp = "\\d{6}") String code
) {
}
