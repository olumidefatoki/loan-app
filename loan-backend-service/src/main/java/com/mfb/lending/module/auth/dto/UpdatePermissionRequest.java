package com.mfb.lending.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdatePermissionRequest(@NotBlank @Size(max = 500) String description) {
}
