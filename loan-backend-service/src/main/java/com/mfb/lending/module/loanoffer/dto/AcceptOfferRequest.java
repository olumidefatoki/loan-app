package com.mfb.lending.module.loanoffer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = """
        Records that the customer read this agreement version and that their KYC identity is verified. \
        agreementRead must be true. This request is an electronic acceptance, not a qualified digital signature.""")
public record AcceptOfferRequest(
        @NotNull Integer version,
        @NotBlank @Size(max = 128) String identityVerificationReference,
        @NotNull Boolean agreementRead,
        @Schema(description = "Optional SHA-256 the customer saw. It must match the stored agreement checksum.") String expectedChecksum
) {
}
