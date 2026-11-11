package com.mfb.lending.module.loanoffer.domain;

import java.time.Instant;
import java.util.UUID;

public record LoanOffer(
        UUID id,
        String reference,
        UUID applicationId,
        UUID customerId,
        OfferStatus status,
        UUID currentVersionId,
        Instant expiresAt,
        Instant acceptedAt,
        Instant declinedAt,
        Instant cancelledAt,
        String decisionReason,
        int version,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {
}
