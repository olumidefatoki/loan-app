package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.util.UUID;

public record RepaymentInstruction(
        UUID applicationId,
        String idempotencyKey,
        BigDecimal amount,
        String providerReference,
        UUID actorId,
        String actorName,
        String correlationId
) {
}
