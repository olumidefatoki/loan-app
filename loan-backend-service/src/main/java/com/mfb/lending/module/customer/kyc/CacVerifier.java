package com.mfb.lending.module.customer.kyc;

/**
 * Provider port for CAC registration verification. Adapters must not invent a vendor URL.
 */
public interface CacVerifier {

    VerificationResult verify(String cacNumber, String registeredName);
}
