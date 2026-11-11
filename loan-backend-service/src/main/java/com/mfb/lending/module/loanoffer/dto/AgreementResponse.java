package com.mfb.lending.module.loanoffer.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AgreementResponse(
        UUID id,
        UUID offerId,
        int offerVersion,
        String kind,
        UUID templateId,
        int templateVersion,
        String checksumSha256,
        long sizeBytes,
        String contentType,
        String fileName,
        Instant frozenAt,
        Instant supersededAt,
        String renderedText,
        String integrity,
        AcceptanceResponse acceptance,
        List<RelatedAgreement> related,
        String signatureDisclaimer
) {

    public record AcceptanceResponse(
            UUID id,
            Instant acceptedAt,
            String identityVerificationReference,
            String kycStatus,
            String signatureProvider,
            String signatureAssurance,
            String signatureReference,
            String disclaimer
    ) {
    }

    public record RelatedAgreement(UUID id, String kind, String checksumSha256, boolean superseded) {
    }
}
