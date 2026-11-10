package com.mfb.lending.module.loanproduct.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductSummary(
        UUID id,
        String productCode,
        ProductCategory category,
        ProductStatus status,
        String productName,
        CustomerSegment customerSegment,
        String currencyCode,
        BigDecimal minimumAmount,
        BigDecimal maximumAmount,
        int minimumTenure,
        int maximumTenure,
        TenureUnit tenureUnit,
        BigDecimal interestRate,
        InterestMethod interestMethod,
        RepaymentFrequency repaymentFrequency,
        int versionNumber
) {
}
