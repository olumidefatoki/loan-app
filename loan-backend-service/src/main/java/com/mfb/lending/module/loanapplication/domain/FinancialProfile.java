package com.mfb.lending.module.loanapplication.domain;

import java.util.List;

public record FinancialProfile(
        IndividualFinancials individual,
        SmeFinancials sme,
        List<Obligation> obligations
) {

    public FinancialProfile {
        obligations = obligations == null ? List.of() : List.copyOf(obligations);
    }

    public static FinancialProfile empty() {
        return new FinancialProfile(null, null, List.of());
    }
}
