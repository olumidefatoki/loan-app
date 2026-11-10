package com.mfb.lending.module.customer.kyc;

/**
 * Provider port for bank account name enquiry. Adapters must not invent a vendor URL.
 */
public interface BankAccountVerifier {

    VerificationResult verify(String bankCode, String accountNumber, String expectedName);
}
