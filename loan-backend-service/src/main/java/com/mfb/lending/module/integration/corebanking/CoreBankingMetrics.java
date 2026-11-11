package com.mfb.lending.module.integration.corebanking;

import io.micrometer.core.instrument.MeterRegistry;

public class CoreBankingMetrics {

    private final MeterRegistry registry;

    public CoreBankingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void call(CoreOperation operation, String outcome) {
        registry.counter("core.banking.calls", "operation", operation.name(), "outcome", outcome).increment();
    }

    public void transition(FinancialTransactionState state) {
        registry.counter("core.banking.transactions", "state", state.name()).increment();
    }

    public void deadLetter() {
        registry.counter("core.banking.dead.letters").increment();
    }

    public void mismatch() {
        registry.counter("core.banking.reconciliation.mismatches").increment();
    }
}
