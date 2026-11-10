package com.mfb.lending.module.customer.kyc;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

@Component
public class MockNinVerifier implements NinVerifier {

    @Override
    public VerificationResult verify(String nin, String fullName, LocalDate dateOfBirth) {
        return MockKycDecisions.identity(nin, "NIN");
    }
}
