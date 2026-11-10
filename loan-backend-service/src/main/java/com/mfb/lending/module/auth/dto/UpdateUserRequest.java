package com.mfb.lending.module.auth.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateUserRequest(
        @Size(max = 255) String fullName,
        @Email @Size(max = 255) String email,
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneNumber,
        @Size(max = 255) String businessName,
        @Pattern(regexp = "ACTIVE|DISABLED") String status,
        List<UUID> branchIds,
        UUID primaryBranchId
) {
}
