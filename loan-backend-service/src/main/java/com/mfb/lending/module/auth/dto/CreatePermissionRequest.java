package com.mfb.lending.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreatePermissionRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 500) String description
) {
}
