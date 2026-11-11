package com.mfb.lending.module.collateral.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public record UpdateCollateralRequest(
        @Size(max = 1000) String description,
        @Size(max = 255) String ownerName,
        @Size(max = 128) String ownerIdentifier,
        @Size(max = 128) String identificationNumber,
        @Size(max = 500) String location,
        @Valid InsuranceRequest insurance
) {
}
