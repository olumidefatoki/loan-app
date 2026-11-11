package com.mfb.lending.module.integration.corebanking;

import java.util.Map;

public interface CoreBankingClient {

    CoreTransportResponse exchange(CoreTransportRequest request);

    record CoreTransportRequest(
            CoreOperation operation,
            String idempotencyKey,
            String transactionReference,
            String correlationId,
            Map<String, Object> attributes
    ) {
    }

    record CoreTransportResponse(
            CoreOutcome outcome,
            String providerReference,
            String message,
            Map<String, Object> attributes
    ) {
    }
}
