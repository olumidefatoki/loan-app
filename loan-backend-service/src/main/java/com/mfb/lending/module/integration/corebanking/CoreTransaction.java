package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record CoreTransaction(
        UUID id,
        String reference,
        String idempotencyKey,
        FinancialTransactionKind kind,
        FinancialTransactionState state,
        InstructionStage stage,
        UUID applicationId,
        UUID customerId,
        UUID accountId,
        UUID relatedTransactionId,
        BigDecimal amount,
        BigDecimal appliedAmount,
        String currencyCode,
        String providerReference,
        String loanAccountReference,
        int loanAttempt,
        int paymentAttempt,
        int version,
        String correlationId,
        String lastError,
        UUID actorId,
        String actorName,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt
) {

    public CoreTransaction apply(TransactionPatch patch, Instant now) {
        return new CoreTransaction(
                id,
                reference,
                idempotencyKey,
                kind,
                patch.state() == null ? state : patch.state(),
                patch.stage() == null ? stage : patch.stage(),
                applicationId,
                customerId,
                accountId,
                relatedTransactionId,
                amount,
                patch.appliedAmount() == null ? appliedAmount : patch.appliedAmount(),
                currencyCode,
                patch.providerReference() == null ? providerReference : patch.providerReference(),
                patch.loanAccountReference() == null ? loanAccountReference : patch.loanAccountReference(),
                patch.loanAttempt() == null ? loanAttempt : patch.loanAttempt(),
                patch.paymentAttempt() == null ? paymentAttempt : patch.paymentAttempt(),
                version + 1,
                correlationId,
                patch.lastError() == null ? lastError : patch.lastError(),
                actorId,
                actorName,
                createdAt,
                now,
                patch.resolvedAt() == null ? resolvedAt : patch.resolvedAt());
    }

    public boolean paymentUnsent() {
        return paymentAttempt == 0;
    }
}
