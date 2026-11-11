package com.mfb.lending.module.loanoffer.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Optional commercial overrides. The interest rate, method, tenure, and fees come from the approved product version.")
public record GenerateOfferRequest(
        @Schema(description = "Principal to offer. Defaults to the approved amount and cannot exceed it.") BigDecimal principal,
        @Schema(description = "Schedule start date. Defaults to the current UTC date.") LocalDate disbursementDate,
        @Schema(description = "Last calendar date the offer can be accepted. Defaults to the configured expiry.") LocalDate expiresOn,
        @Schema(description = "When false, the offer stays in DRAFT. Defaults to true and issues the offer.") Boolean issue
) {
}
