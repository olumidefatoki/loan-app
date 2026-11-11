package com.mfb.lending.module.loanoffer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OfferReasonRequest(
        @NotNull Integer version,
        @NotBlank @Size(max = 500) String reason
) {
}
