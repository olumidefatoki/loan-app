package com.mfb.lending.module.loanoffer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LoanOfferSummaryResponse(
        UUID id,
        String reference,
        UUID applicationId,
        String applicationReference,
        String productName,
        String status,
        BigDecimal principal,
        String currencyCode,
        BigDecimal instalmentAmount,
        Instant expiresAt,
        int version
) {
}
