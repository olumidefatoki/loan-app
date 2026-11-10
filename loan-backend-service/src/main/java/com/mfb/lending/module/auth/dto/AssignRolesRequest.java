package com.mfb.lending.module.auth.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record AssignRolesRequest(@NotEmpty List<@NotBlank String> roleCodes) {
}
