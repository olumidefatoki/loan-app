package com.mfb.lending.module.customer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record AccountVerifyRequest(
        @NotBlank @Pattern(regexp = "\\d{3,6}") String bankCode,
        @NotBlank @Pattern(regexp = "\\d{10}") String accountNumber
) {
}
