package com.mfb.lending.module.customer.dto;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Creates an individual or SME customer. Web and mobile clients use this same JSON body.")
public record CreateCustomerRequest(
        @NotBlank @Pattern(regexp = "INDIVIDUAL|SME") String customerType,
        @Pattern(regexp = "RETAIL|MICRO|SMALL|MEDIUM|CORPORATE") String category,
        UUID branchId,
        @NotBlank @Size(max = 255) String displayName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneNumber,
        UUID userId,
        @Valid List<ConsentRequest> consents
) {
}
