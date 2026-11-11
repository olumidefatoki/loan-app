package com.mfb.lending.module.integration.corebanking.api;

import com.mfb.lending.module.integration.corebanking.CoreBankingHealthService;
import com.mfb.lending.module.integration.corebanking.CorePostingEngine;
import com.mfb.lending.module.integration.corebanking.CoreTransaction;
import com.mfb.lending.module.integration.corebanking.dto.CoreBankingHealthResponse;
import com.mfb.lending.module.integration.corebanking.dto.CoreTransactionResponse;
import com.mfb.lending.module.integration.corebanking.dto.ReconciliationResponse;

final class CoreBankingResponses {

    private CoreBankingResponses() {
    }

    static CoreTransactionResponse transaction(CoreTransaction transaction) {
        return new CoreTransactionResponse(
                transaction.id(),
                transaction.reference(),
                transaction.idempotencyKey(),
                transaction.kind().name(),
                transaction.state().name(),
                transaction.stage().name(),
                transaction.applicationId(),
                transaction.customerId(),
                transaction.amount(),
                transaction.appliedAmount(),
                transaction.currencyCode(),
                transaction.providerReference(),
                transaction.loanAccountReference(),
                transaction.loanAttempt(),
                transaction.paymentAttempt(),
                transaction.lastError(),
                transaction.createdAt(),
                transaction.updatedAt(),
                transaction.resolvedAt());
    }

    static CoreBankingHealthResponse health(CoreBankingHealthService.Health health) {
        return new CoreBankingHealthResponse(
                health.provider(),
                health.status(),
                health.circuit(),
                health.remoteCallsEnabled(),
                health.pending(),
                health.unknown(),
                health.reconciliationRequired(),
                health.deadLetters());
    }

    static ReconciliationResponse reconciliation(CorePostingEngine.ReconciliationResult result) {
        return new ReconciliationResponse(
                result.batchId(),
                result.transaction().id(),
                result.transaction().state().name(),
                result.itemStatus(),
                result.expectedAmount(),
                result.actualAmount());
    }
}
