package com.mfb.lending.module.customer.kyc;

import java.time.LocalDate;

/**
 * Provider port for NIN verification. Adapters must not invent a vendor URL.
 */
public interface NinVerifier {

    VerificationResult verify(String nin, String fullName, LocalDate dateOfBirth);
}
