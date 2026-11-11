package com.mfb.lending.module.collateral.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReleaseCollateralRequest(
        @NotBlank @Size(max = 128) String settlementReference,
        @NotBlank @Size(max = 128) String dischargeReference,
        @NotBlank @Size(max = 500) String reason
) {
}
