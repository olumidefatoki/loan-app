package com.mfb.lending.module.servicing.application;

import java.math.BigDecimal;
import java.util.UUID;

import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.integration.corebanking.CoreBankingStore;
import com.mfb.lending.module.integration.corebanking.CoreRepaymentService;
import com.mfb.lending.module.integration.corebanking.CoreTransaction;
import com.mfb.lending.module.integration.corebanking.FinancialTransactionKind;
import com.mfb.lending.module.integration.corebanking.FinancialTransactionState;
import com.mfb.lending.module.integration.corebanking.RepaymentInstruction;
import org.springframework.stereotype.Component;

@Component
public class CoreCollectionAdapter implements CoreCollectionPort {

    private final CoreRepaymentService repayments;
    private final CoreBankingStore store;

    public CoreCollectionAdapter(CoreRepaymentService repayments, CoreBankingStore store) {
        this.repayments = repayments;
        this.store = store;
    }

    @Override
    public Result collect(UUID applicationId, String idempotencyKey, BigDecimal amount, UUID actorId, String actorName) {
        CoreTransaction transaction = repayments.collectForServicing(
                new RepaymentInstruction(applicationId, idempotencyKey, amount, null, actorId, actorName, null),
                amount,
                new ClientContext(null, null));
        return map(transaction);
    }

    @Override
    public Result reverse(UUID coreTransactionId, String idempotencyKey, UUID actorId, String actorName) {
        CoreTransaction transaction = repayments.reverse(coreTransactionId, idempotencyKey, actorId, actorName, new ClientContext(null, null));
        return map(transaction);
    }

    @Override
    public BigDecimal confirmedServicingCash(UUID applicationId) {
        return store.servicingRepaymentCash(applicationId);
    }

    private static Result map(CoreTransaction transaction) {
        FinancialTransactionState state = transaction.state();
        boolean reversed = state == FinancialTransactionState.REVERSED
                || (transaction.kind() == FinancialTransactionKind.REVERSAL && state.countsAsPosted());
        return new Result(
                transaction.id(),
                transaction.reference(),
                state.name(),
                state.countsAsPosted() && transaction.kind() == FinancialTransactionKind.REPAYMENT,
                state == FinancialTransactionState.FAILED,
                reversed);
    }
}
