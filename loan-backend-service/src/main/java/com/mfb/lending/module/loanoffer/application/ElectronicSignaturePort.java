package com.mfb.lending.module.loanoffer.application;

import java.time.Instant;
import java.util.UUID;

public interface ElectronicSignaturePort {

    SignatureEvidence accept(SignatureCommand command);

    record SignatureCommand(
            UUID offerId,
            UUID agreementId,
            String checksumSha256,
            UUID customerId,
            String identityVerificationReference,
            Instant acceptedAt
    ) {
    }

    record SignatureEvidence(
            String provider,
            String assurance,
            String reference,
            Instant signedAt,
            String disclaimer
    ) {
    }
}
