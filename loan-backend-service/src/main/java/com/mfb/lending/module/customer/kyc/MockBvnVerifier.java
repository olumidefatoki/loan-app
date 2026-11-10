package com.mfb.lending.module.customer.kyc;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

@Component
public class MockBvnVerifier implements BvnVerifier {

    @Override
    public VerificationResult verify(String bvn, String fullName, LocalDate dateOfBirth) {
        return MockKycDecisions.identity(bvn, "BVN");
    }
}
