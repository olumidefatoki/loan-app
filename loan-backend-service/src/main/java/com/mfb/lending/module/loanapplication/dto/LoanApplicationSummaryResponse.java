package com.mfb.lending.module.loanapplication.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LoanApplicationSummaryResponse(
        UUID id,
        String reference,
        UUID customerId,
        String customerName,
        String customerSegment,
        UUID productId,
        String productCode,
        String productName,
        String status,
        BigDecimal amount,
        String currencyCode,
        Integer tenure,
        String tenureUnit,
        String repaymentFrequency,
        int revisionNumber,
        boolean correctionOpen,
        String channel,
        Instant submittedAt,
        Instant updatedAt
) {
}
