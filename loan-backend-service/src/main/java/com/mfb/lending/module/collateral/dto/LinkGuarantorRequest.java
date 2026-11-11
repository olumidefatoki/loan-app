package com.mfb.lending.module.collateral.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LinkGuarantorRequest(
        @NotNull UUID guarantorId,
        @NotBlank @Size(max = 80) String relationship
) {
}
