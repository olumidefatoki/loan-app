package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CoreBankingStore {

    Optional<CoreTransaction> findById(UUID id);

    Optional<CoreTransaction> findByIdempotency(String idempotencyKey);

    Optional<CoreTransaction> findByReference(String reference);

    Optional<CoreTransaction> findOpenDisbursement(UUID applicationId);

    CoreTransaction insert(CoreTransaction transaction);

    CoreTransaction transition(UUID id, int expectedVersion, FinancialEvent event, TransactionPatch patch, String reason);

    Optional<CoreTransaction> claimLoanRegistration(UUID id);

    Optional<CoreTransaction> claimPayment(UUID id);

    CoreTransaction annotate(UUID id, int expectedVersion, TransactionPatch patch);

    CoreTransaction revertClaim(UUID id, int expectedVersion, boolean loanClaim);

    String nextReference();

    List<CoreTransaction> list(String state, String kind, int limit, int offset);

    long count(String state, String kind);

    BigDecimal postedNet(UUID applicationId);

    void markServicingCollection(UUID transactionId);

    boolean servicingCollection(UUID transactionId);

    BigDecimal servicingRepaymentCash(UUID applicationId);

    int countState(FinancialTransactionState state);

    UUID insertRequest(UUID transactionId, String operation, String idempotencyKey, String correlationId, String payload);

    void insertResponse(UUID requestId, String outcome, String providerReference, String payload, int latencyMs);

    boolean insertWebhook(UUID id, String messageId, String eventType, String payload);

    void insertInbox(String messageId, String eventType, String payload);

    UUID insertOutbox(UUID transactionId, String eventType, String payload);

    void completeOutbox(UUID outboxId, String status, String error, Instant availableAt);

    List<OutboxMessage> claimDueOutbox(int limit);

    void insertError(String operation, String reference, String category, String message, boolean deadLetter, String payload);

    List<DeadLetter> unresolvedDeadLetters();

    void resolveErrors(String reference);

    UUID openBatch();

    void addItem(
            UUID batchId,
            UUID transactionId,
            String externalReference,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            String status,
            String detail);

    void closeBatch(UUID batchId, int mismatches);

    int countDeadLetters();

    default CoreTransaction require(UUID id) {
        return findById(id).orElseThrow(() -> new com.mfb.lending.common.exception.ResourceNotFoundException("Core banking transaction"));
    }

    record OutboxMessage(UUID id, UUID transactionId, String eventType) {
    }

    record DeadLetter(UUID id, String operation, String reference, String category, String message) {
    }
}
