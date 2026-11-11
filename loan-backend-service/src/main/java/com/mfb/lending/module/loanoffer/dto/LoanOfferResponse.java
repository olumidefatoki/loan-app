package com.mfb.lending.module.loanoffer.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LoanOfferResponse(
        UUID id,
        String reference,
        UUID applicationId,
        String applicationReference,
        UUID customerId,
        String customerName,
        String customerReference,
        String status,
        Instant expiresAt,
        Instant acceptedAt,
        int version,
        int offerVersion,
        String engineVersion,
        String calculationFingerprint,
        TermsResponse terms,
        UUID agreementId,
        String agreementChecksum,
        String signatureDisclaimer
) {

    public record TermsResponse(
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
            List<FeeResponse> fees,
            String disbursementConditions,
            String collateralDetails,
            String guarantorDetails,
            String defaultProvisions
    ) {
    }

    public record FeeResponse(
            String type,
            String name,
            String calculation,
            BigDecimal amount,
            BigDecimal rate,
            BigDecimal charged
    ) {
    }
}
