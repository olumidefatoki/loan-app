package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.util.UUID;

public record DisbursementInstruction(
        UUID applicationId,
        String idempotencyKey,
        BigDecimal amount,
        UUID accountId,
        UUID actorId,
        String actorName,
        String correlationId
) {
}
