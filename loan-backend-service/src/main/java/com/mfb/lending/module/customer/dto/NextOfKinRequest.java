package com.mfb.lending.module.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record NextOfKinRequest(
        @NotBlank @Size(max = 255) String fullName,
        @NotBlank @Size(max = 64) String relationship,
        @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneNumber,
        @NotBlank @Size(max = 500) String address
) {
}
