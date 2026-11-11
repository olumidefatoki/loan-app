package com.mfb.lending.module.approval.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ApprovalHistoryResponse(
        UUID id,
        UUID actorId,
        String role,
        String stage,
        String decision,
        BigDecimal amount,
        String reason,
        String correlationId,
        Instant occurredAt
) {
}
