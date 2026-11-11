package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.KycStatus;

public record DecisionInput(
        CustomerStatus customerStatus,
        KycStatus kycStatus,
        boolean blacklisted,
        boolean complianceHold,
        boolean productEligible,
        BigDecimal amount,
        BigDecimal minimumAmount,
        BigDecimal maximumAmount,
        boolean collateralRequired,
        BigDecimal productMinimumCoverage,
        boolean creditBureauRequired,
        Integer productMinimumBureauScore,
        BureauView bureau,
        AssessmentFacts facts,
        List<CreditRule> rules,
        Set<String> approvedExceptions,
        ScoringWeights weights,
        List<RiskBand> riskCategories,
        DecisionThresholds thresholds,
        UUID modelId,
        UUID modelVersionId,
        String policyVersion
) {

    public DecisionInput {
        rules = rules == null ? List.of() : List.copyOf(rules);
        approvedExceptions = approvedExceptions == null ? Set.of() : Set.copyOf(approvedExceptions);
        riskCategories = riskCategories == null ? List.of() : List.copyOf(riskCategories);
        bureau = bureau == null ? BureauView.none() : bureau;
    }
}
