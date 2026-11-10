package com.mfb.lending.module.customer.kyc;

import org.springframework.stereotype.Component;

@Component
public class MockCacVerifier implements CacVerifier {

    @Override
    public VerificationResult verify(String cacNumber, String registeredName) {
        return MockKycDecisions.registration(cacNumber, "CAC");
    }
}
