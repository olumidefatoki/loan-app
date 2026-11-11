package com.mfb.lending.module.collateral.dto;

import com.mfb.lending.module.collateral.domain.GuaranteeDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record GuaranteeAcceptanceRequest(
        @NotNull GuaranteeDecision decision,
        @Size(max = 500) String notes
) {
}
