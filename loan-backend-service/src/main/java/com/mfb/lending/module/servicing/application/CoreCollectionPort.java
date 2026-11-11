package com.mfb.lending.module.servicing.application;

import java.math.BigDecimal;
import java.util.UUID;

public interface CoreCollectionPort {

    Result collect(UUID applicationId, String idempotencyKey, BigDecimal amount, UUID actorId, String actorName);

    Result reverse(UUID coreTransactionId, String idempotencyKey, UUID actorId, String actorName);

    BigDecimal confirmedServicingCash(UUID applicationId);

    record Result(
            UUID transactionId,
            String reference,
            String state,
            boolean posted,
            boolean failed,
            boolean reversed
    ) {
    }
}
