package com.mfb.lending.module.loanproduct.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = "Creates a draft loan product and its first version.")
public record CreateLoanProductRequest(
        @NotBlank
        @Size(min = 2, max = 32)
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,31}$")
        @Schema(description = "Permanent unique code. Uppercase letters, digits, and underscores.", example = "SALARY_ADVANCE")
        String productCode,
        @NotBlank
        @Schema(description = "PERSONAL, SALARY_ADVANCE, EMERGENCY, MICROENTERPRISE, SME_WORKING_CAPITAL, BUSINESS_EXPANSION, INVENTORY_FINANCING, ASSET_FINANCING, AGRICULTURAL, SECURED, or UNSECURED")
        String category,
        @NotNull @Valid CommercialTermsRequest terms
) {
}
