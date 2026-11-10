package com.mfb.lending.module.loanproduct.domain;

import java.util.List;

public record ProductComponents(
        List<ProductFee> fees,
        List<ProductDocumentRequirement> documents,
        List<EligibilityRule> eligibilityRules,
        List<CollateralRule> collateralRules,
        List<ApprovalRule> approvalRules
) {
}
