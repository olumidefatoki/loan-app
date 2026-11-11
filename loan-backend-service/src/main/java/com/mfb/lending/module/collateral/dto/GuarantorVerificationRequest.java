package com.mfb.lending.module.collateral.dto;

import com.mfb.lending.module.collateral.domain.GuarantorDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GuarantorVerificationRequest(
        @NotNull GuarantorDecision decision,
        @Size(max = 500) String notes
) {
}
