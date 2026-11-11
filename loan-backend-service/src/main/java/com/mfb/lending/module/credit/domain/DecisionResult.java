package com.mfb.lending.module.credit.domain;

import java.util.List;

public record DecisionResult(
        DecisionOutcome outcome,
        List<DecisionReason> reasons,
        ScoreResult score,
        CalculationSet calculations
) {

    public DecisionResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
