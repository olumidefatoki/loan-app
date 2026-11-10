package com.mfb.lending.module.customer.kyc;

import com.mfb.lending.module.customer.domain.CheckOutcome;

public record VerificationResult(CheckOutcome outcome, String provider, String reference, String detail) {
}
