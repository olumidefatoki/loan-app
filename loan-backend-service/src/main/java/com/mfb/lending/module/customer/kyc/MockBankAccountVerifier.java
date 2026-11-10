package com.mfb.lending.module.customer.kyc;

import org.springframework.stereotype.Component;

@Component
public class MockBankAccountVerifier implements BankAccountVerifier {

    @Override
    public VerificationResult verify(String bankCode, String accountNumber, String expectedName) {
        return MockKycDecisions.identity(accountNumber, "Bank account");
    }
}
