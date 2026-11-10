package com.mfb.lending.module.customer.kyc;

import java.util.List;

import com.mfb.lending.module.customer.domain.RiskClassification;

public record AmlDecision(RiskClassification classification, int score, List<String> factors, String detail) {

    public AmlDecision {
        factors = factors == null ? List.of() : List.copyOf(factors);
    }
}
