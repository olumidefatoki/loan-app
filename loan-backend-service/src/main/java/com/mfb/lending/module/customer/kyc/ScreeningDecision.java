package com.mfb.lending.module.customer.kyc;

import com.mfb.lending.module.customer.domain.ScreeningOutcome;

public record ScreeningDecision(ScreeningOutcome outcome, String provider, String detail) {
}
