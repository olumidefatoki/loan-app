package com.mfb.lending.module.loanoffer.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record OfferTerms(
        UUID id,
        UUID offerVersionId,
        UUID customerId,
        String customerName,
        String customerReference,
        String applicationReference,
        UUID productId,
        String productCode,
        String productName,
        UUID productVersionId,
        BigDecimal principal,
        String currencyCode,
        BigDecimal interestRate,
        String interestMethod,
        String interestPeriod,
        int tenure,
        String tenureUnit,
        String repaymentFrequency,
        String dayCountConvention,
        int roundingPrecision,
        String roundingMethod,
        int gracePeriodDays,
        BigDecimal instalmentAmount,
        BigDecimal processingFees,
        BigDecimal otherFees,
        BigDecimal taxes,
        BigDecimal taxRate,
        BigDecimal totalInterest,
        BigDecimal totalRepayment,
        BigDecimal netDisbursement,
        LocalDate disbursementDate,
        String engineVersion,
        String feeBreakdown,
        String disbursementConditions,
        String collateralDetails,
        String guarantorDetails,
        String defaultProvisions
) {
}
