package com.mfb.lending.module.customer.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScreeningResponse(
        UUID customerId,
        String riskClassification,
        Integer riskScore,
        List<Result> results
) {

    public record Result(
            UUID id,
            String screeningType,
            String outcome,
            String provider,
            String detail,
            Instant screenedAt
    ) {
    }
}
