package com.mfb.lending.module.integration.corebanking;

import java.time.Instant;
import java.util.UUID;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CoreBankingOutboxDispatcher {

    private final CoreBankingStore store;
    private final CorePostingEngine engine;
    private final UnitOfWork unitOfWork;
    private final java.time.Clock clock;

    public CoreBankingOutboxDispatcher(
            CoreBankingStore store,
            CorePostingEngine engine,
            UnitOfWork unitOfWork,
            java.time.Clock clock) {
        this.store = store;
        this.engine = engine;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.core-banking.outbox-poll-ms:15000}")
    public void poll() {
        dispatchDue();
    }

    public void dispatchDue() {
        var due = unitOfWork.required(() -> store.claimDueOutbox(20));
        for (CoreBankingStore.OutboxMessage message : due) {
            try {
                CoreTransaction result = switch (message.eventType()) {
                    case "DisbursementRequested" -> engine.submitDisbursement(message.transactionId());
                    case "RepaymentRequested" -> engine.submitRepayment(message.transactionId());
                    case "ReversalRequested" -> engine.submitReversal(message.transactionId());
                    default -> store.require(message.transactionId());
                };
                finish(message.id(), result);
            } catch (ApiException ex) {
                if (ex.getErrorCode() == ErrorCode.SERVICE_UNAVAILABLE) {
                    finish(message.id(), store.require(message.transactionId()));
                } else {
                    store.completeOutbox(message.id(), "FAILED", ex.getMessage(), null);
                    store.insertError(message.eventType(), message.transactionId().toString(), "DISPATCH", ex.getMessage(), true, "{}");
                }
            }
        }
    }

    public void finish(UUID outboxId, CoreTransaction result) {
        if (result.state() == FinancialTransactionState.PENDING) {
            store.completeOutbox(outboxId, "PENDING", result.lastError(), Instant.now(clock).plusSeconds(30));
            return;
        }
        if (result.state() == FinancialTransactionState.UNKNOWN || result.state() == FinancialTransactionState.SUBMITTED) {
            store.completeOutbox(outboxId, "FAILED", "Enquiry is required before another submission", null);
            return;
        }
        store.completeOutbox(outboxId, "PUBLISHED", null, null);
    }
}
