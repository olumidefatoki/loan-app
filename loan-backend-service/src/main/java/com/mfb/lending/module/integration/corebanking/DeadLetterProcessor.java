package com.mfb.lending.module.integration.corebanking;

import org.springframework.stereotype.Service;

@Service
public class DeadLetterProcessor {

    private final CoreBankingStore store;
    private final CorePostingEngine engine;

    public DeadLetterProcessor(CoreBankingStore store, CorePostingEngine engine) {
        this.store = store;
        this.engine = engine;
    }

    public int process() {
        int resolved = 0;
        for (CoreBankingStore.DeadLetter letter : store.unresolvedDeadLetters()) {
            if (letter.reference() == null) {
                continue;
            }
            var transaction = store.findByReference(letter.reference());
            if (transaction.isEmpty()) {
                continue;
            }
            CoreTransaction after;
            try {
                after = engine.enquire(transaction.get().id());
            } catch (RuntimeException ex) {
                continue;
            }
            if (after.state() != FinancialTransactionState.UNKNOWN
                    && after.state() != FinancialTransactionState.SUBMITTED
                    && after.state() != FinancialTransactionState.PENDING) {
                store.resolveErrors(after.reference());
                resolved++;
            }
        }
        return resolved;
    }
}
