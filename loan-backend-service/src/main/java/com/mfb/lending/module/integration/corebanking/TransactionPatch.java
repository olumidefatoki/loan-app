package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.time.Instant;

public record TransactionPatch(
        FinancialTransactionState state,
        InstructionStage stage,
        String providerReference,
        String loanAccountReference,
        BigDecimal appliedAmount,
        Integer loanAttempt,
        Integer paymentAttempt,
        String lastError,
        Instant resolvedAt
) {

    public static TransactionPatch state(FinancialTransactionState state, String lastError, Instant resolvedAt) {
        return new TransactionPatch(state, null, null, null, null, null, null, lastError, resolvedAt);
    }
}
