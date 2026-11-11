package com.mfb.lending.module.integration.corebanking;

import com.mfb.lending.config.CoreBankingProperties;
import org.springframework.stereotype.Service;

@Service
public class CoreBankingHealthService {

    private final CoreBankingProperties properties;
    private final CoreBankingStore store;
    private final IntegrationCircuitBreaker circuit;

    public CoreBankingHealthService(CoreBankingProperties properties, CoreBankingStore store, IntegrationCircuitBreaker circuit) {
        this.properties = properties;
        this.store = store;
        this.circuit = circuit;
    }

    public Health health() {
        int pending = store.countState(FinancialTransactionState.PENDING);
        int unknown = store.countState(FinancialTransactionState.UNKNOWN);
        int reconciliation = store.countState(FinancialTransactionState.RECONCILIATION_REQUIRED);
        int deadLetters = store.countDeadLetters();
        String position = circuit.position();
        String status = "OPEN".equals(position) || unknown > 0 || deadLetters > 0 ? "DEGRADED" : "UP";
        return new Health(properties.getProvider(), status, position, properties.http(), pending, unknown, reconciliation, deadLetters);
    }

    public record Health(
            String provider,
            String status,
            String circuit,
            boolean remoteCallsEnabled,
            int pending,
            int unknown,
            int reconciliationRequired,
            int deadLetters
    ) {
    }
}
