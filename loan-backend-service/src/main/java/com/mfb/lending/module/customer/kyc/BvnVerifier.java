package com.mfb.lending.module.customer.kyc;

import java.time.LocalDate;

/**
 * Provider port for BVN verification. Adapters must not invent a vendor URL.
 * Development and test use {@code MockBvnVerifier}.
 */
public interface BvnVerifier {

    VerificationResult verify(String bvn, String fullName, LocalDate dateOfBirth);
}
