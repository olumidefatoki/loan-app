package com.mfb.lending.module.integration.corebanking.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CoreTransactionResponse(
        UUID id,
        String reference,
        String idempotencyKey,
        String kind,
        String state,
        String stage,
        UUID applicationId,
        UUID customerId,
        BigDecimal amount,
        BigDecimal appliedAmount,
        String currencyCode,
        String providerReference,
        String loanAccountReference,
        int loanAttempt,
        int paymentAttempt,
        String lastError,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt
) {
}
