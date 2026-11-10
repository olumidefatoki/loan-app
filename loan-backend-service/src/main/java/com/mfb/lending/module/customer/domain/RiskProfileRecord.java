package com.mfb.lending.module.customer.domain;

import java.time.Instant;
import java.util.UUID;

public record RiskProfileRecord(
        UUID customerId,
        RiskClassification classification,
        int score,
        Instant assessedAt
) {
}
