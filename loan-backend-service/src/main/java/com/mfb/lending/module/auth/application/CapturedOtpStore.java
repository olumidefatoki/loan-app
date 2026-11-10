package com.mfb.lending.module.auth.application;

import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "app.auth.otp", name = "capture-enabled", havingValue = "true")
public class CapturedOtpStore {

    private final ConcurrentHashMap<String, String> codes = new ConcurrentHashMap<>();

    public void put(String destination, String purpose, String code) {
        codes.put(destination + "|" + purpose, code);
    }

    public String latest(String destination, String purpose) {
        return codes.get(destination + "|" + purpose);
    }
}
