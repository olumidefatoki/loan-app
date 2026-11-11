package com.mfb.lending.module.integration.corebanking.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record ReconciliationResponse(
        UUID batchId,
        UUID transactionId,
        String transactionState,
        String itemStatus,
        BigDecimal expectedAmount,
        BigDecimal actualAmount
) {
}
