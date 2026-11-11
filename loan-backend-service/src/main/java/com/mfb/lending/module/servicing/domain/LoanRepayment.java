package com.mfb.lending.module.servicing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LoanRepayment(
        UUID id,
        String reference,
        UUID loanAccountId,
        String idempotencyKey,
        RepaymentChannel channel,
        RepaymentStatus status,
        BigDecimal amount,
        LocalDate valueDate,
        boolean settlement,
        BigDecimal feeCharged,
        UUID coreTransactionId,
        String coreReference,
        String externalReference,
        UUID cashJournalId,
        UUID feeJournalId,
        int allocationPolicyVersion,
        int version,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        UUID actorId,
        String actorName
) {
}
