package com.mfb.lending.module.loanoffer.domain;

import java.time.Instant;
import java.util.UUID;

public record AgreementAcceptance(
        UUID id,
        UUID agreementId,
        UUID offerId,
        UUID customerId,
        Instant acceptedAt,
        String identityVerificationReference,
        String kycStatus,
        String signatureProvider,
        String signatureAssurance,
        String signatureReference,
        String disclaimer,
        String channel,
        String ipAddress,
        String userAgent,
        UUID actorId
) {
}
