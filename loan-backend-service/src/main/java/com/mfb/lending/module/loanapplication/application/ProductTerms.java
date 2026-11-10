package com.mfb.lending.module.loanapplication.application;

import java.math.BigDecimal;
import java.util.List;

import com.mfb.lending.module.loanproduct.domain.CollateralRule;
import com.mfb.lending.module.loanproduct.domain.CustomerSegment;
import com.mfb.lending.module.loanproduct.domain.EligibilityRule;
import com.mfb.lending.module.loanproduct.domain.ProductDocumentRequirement;
import com.mfb.lending.module.loanproduct.domain.ProductStatus;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;

public record ProductTerms(
        ProductStatus productStatus,
        ProductStatus versionStatus,
        CustomerSegment customerSegment,
        BigDecimal minimumAmount,
        BigDecimal maximumAmount,
        int minimumTenure,
        int maximumTenure,
        TenureUnit tenureUnit,
        RepaymentFrequency repaymentFrequency,
        boolean guarantorRequired,
        int minimumGuarantors,
        List<ProductDocumentRequirement> documents,
        List<CollateralRule> collateralRules,
        List<EligibilityRule> eligibilityRules
) {

    public ProductTerms {
        documents = documents == null ? List.of() : List.copyOf(documents);
        collateralRules = collateralRules == null ? List.of() : List.copyOf(collateralRules);
        eligibilityRules = eligibilityRules == null ? List.of() : List.copyOf(eligibilityRules);
    }

    public boolean offered() {
        return productStatus == ProductStatus.ACTIVE && versionStatus == ProductStatus.ACTIVE;
    }
}
