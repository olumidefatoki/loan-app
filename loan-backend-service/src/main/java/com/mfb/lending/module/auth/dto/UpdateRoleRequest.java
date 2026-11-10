package com.mfb.lending.module.auth.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateRoleRequest(
        @NotBlank @Size(max = 128) String name,
        @Size(max = 500) String description,
        boolean requiresMfa,
        boolean unrestrictedBranches,
        List<@NotBlank String> permissionCodes
) {
}
