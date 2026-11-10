package com.mfb.lending.module.customer.dto;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Updates the customer header. Omit a field to leave it unchanged. Customers can change their own contact details. Staff can also change category, branch, status, blacklist, and risk classification.")
public record UpdateCustomerRequest(
        @Size(max = 255) String displayName,
        @Email @Size(max = 255) String email,
        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneNumber,
        @Pattern(regexp = "RETAIL|MICRO|SMALL|MEDIUM|CORPORATE") String category,
        UUID branchId,
        @Pattern(regexp = "PENDING|ACTIVE|INACTIVE|BLACKLISTED") String status,
        @Size(max = 500) String blacklistReason,
        @Pattern(regexp = "LOW|MEDIUM|HIGH|UNACCEPTABLE") String riskClassification
) {
}
