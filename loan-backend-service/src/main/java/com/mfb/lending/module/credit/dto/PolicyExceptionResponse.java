package com.mfb.lending.module.credit.dto;

import java.time.Instant;
import java.util.UUID;

public record PolicyExceptionResponse(
        UUID id,
        String ruleCode,
        String status,
        String justification,
        UUID requestedBy,
        Instant requestedAt,
        UUID decidedBy,
        Instant decidedAt,
        String decisionNote
) {
}
