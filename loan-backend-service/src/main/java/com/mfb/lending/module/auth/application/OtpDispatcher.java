package com.mfb.lending.module.auth.application;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.module.auth.domain.OtpChannel;
import com.mfb.lending.module.auth.domain.OtpPurpose;
import com.mfb.lending.module.auth.security.AesGcmCipher;
import com.mfb.lending.module.integration.persistence.IntegrationOutboxRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class OtpDispatcher {

    private final IntegrationOutboxRepository outbox;
    private final AesGcmCipher cipher;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<CapturedOtpStore> capturedOtps;

    public OtpDispatcher(
            IntegrationOutboxRepository outbox,
            AesGcmCipher cipher,
            ObjectMapper objectMapper,
            ObjectProvider<CapturedOtpStore> capturedOtps) {
        this.outbox = outbox;
        this.cipher = cipher;
        this.objectMapper = objectMapper;
        this.capturedOtps = capturedOtps;
    }

    public void dispatch(String userId, OtpPurpose purpose, OtpChannel channel, String destination, String code) {
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "purpose", purpose.name(),
                    "channel", channel.name(),
                    "destination", destination,
                    "ciphertext", cipher.encrypt(code)));
            outbox.insert("system_user", userId, "OtpRequested", payload, channel.name());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("OTP dispatch payload could not be written", ex);
        }
        capturedOtps.ifAvailable(store -> store.put(destination, purpose.name(), code));
    }
}
