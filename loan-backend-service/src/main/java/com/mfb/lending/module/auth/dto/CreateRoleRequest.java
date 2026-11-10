package com.mfb.lending.module.auth.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record CreateRoleRequest(
        @NotBlank @Size(max = 64) String code,
        @NotBlank @Size(max = 128) String name,
        @Size(max = 500) String description,
        boolean requiresMfa,
        boolean unrestrictedBranches,
        @NotEmpty List<@NotBlank String> permissionCodes
) {
}
