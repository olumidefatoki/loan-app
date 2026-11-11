package com.mfb.lending.module.loanoffer.application;

import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.config.OfferProperties;
import com.mfb.lending.module.loanoffer.domain.SignatureAssurance;
import org.springframework.stereotype.Component;

@Component
public class RecordedAcceptanceSignatureAdapter implements ElectronicSignaturePort {

    static final String PROVIDER = "PLATFORM_RECORD";

    private final OfferProperties properties;

    public RecordedAcceptanceSignatureAdapter(OfferProperties properties) {
        this.properties = properties;
    }

    @Override
    public SignatureEvidence accept(SignatureCommand command) {
        if (properties.getSignature().getMode() == OfferProperties.SignatureMode.EXTERNAL) {
            throw new ConflictException(SignatureStatements.PROVIDER_REQUIRED);
        }
        return new SignatureEvidence(
                PROVIDER,
                SignatureAssurance.RECORDED_ACCEPTANCE.name(),
                UUID.randomUUID().toString(),
                command.acceptedAt(),
                SignatureStatements.RECORDED_ACCEPTANCE);
    }
}
