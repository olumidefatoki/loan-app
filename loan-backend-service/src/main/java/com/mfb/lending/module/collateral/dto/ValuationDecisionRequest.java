package com.mfb.lending.module.collateral.dto;

import com.mfb.lending.module.collateral.domain.ValuationDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ValuationDecisionRequest(
        @NotNull ValuationDecision decision,
        @Size(max = 500) String reason
) {
}
