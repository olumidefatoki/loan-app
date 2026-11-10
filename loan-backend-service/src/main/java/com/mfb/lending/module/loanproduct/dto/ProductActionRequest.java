package com.mfb.lending.module.loanproduct.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Optional note and effective date for a product lifecycle action.")
public record ProductActionRequest(
        @Size(max = 500) String reason,
        @Schema(description = "When the version becomes available. Defaults to the current time. Used by activation.")
        Instant effectiveFrom
) {
}
