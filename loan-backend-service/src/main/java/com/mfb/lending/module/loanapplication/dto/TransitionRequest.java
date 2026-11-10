package com.mfb.lending.module.loanapplication.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TransitionRequest(
        @NotBlank
        @Schema(description = "Target status. Customer-controlled statuses SUBMITTED, WITHDRAWN, and OFFER_ACCEPTED are not accepted here.")
        String status,
        @Size(max = 500)
        @Schema(description = "Required when returning for correction, referring, holding, rejecting, cancelling, or expiring an application.")
        String reason
) {
}
