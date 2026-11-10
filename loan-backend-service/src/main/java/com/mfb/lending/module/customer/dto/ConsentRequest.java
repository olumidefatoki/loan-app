package com.mfb.lending.module.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ConsentRequest(
        @NotBlank @Pattern(regexp = "DATA_PROCESSING|IDENTITY_VERIFICATION|CREDIT_BUREAU|MARKETING") String type,
        boolean granted,
        @NotBlank @Size(max = 32) String version
) {
}
