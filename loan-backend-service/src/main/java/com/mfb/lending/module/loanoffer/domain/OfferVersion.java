package com.mfb.lending.module.loanoffer.domain;

import java.time.Instant;
import java.util.UUID;

public record OfferVersion(
        UUID id,
        UUID offerId,
        int versionNumber,
        String fingerprint,
        String engineVersion,
        Instant supersededAt,
        Instant createdAt,
        String createdBy
) {
}
