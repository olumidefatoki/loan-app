package com.mfb.lending.module.auth.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateStaffRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneNumber,
        @NotBlank @Size(min = 12, max = 128) String password,
        @NotEmpty List<@NotBlank String> roleCodes,
        List<UUID> branchIds,
        UUID primaryBranchId
) {
}
