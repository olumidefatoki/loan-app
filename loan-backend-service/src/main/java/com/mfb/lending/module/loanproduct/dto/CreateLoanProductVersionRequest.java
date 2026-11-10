package com.mfb.lending.module.loanproduct.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Opens the next draft version. The current active version stays in force for existing and new loans until this version is activated.")
public record CreateLoanProductVersionRequest(
        @Size(max = 500) String changeReason,
        @NotNull @Valid CommercialTermsRequest terms
) {
}
