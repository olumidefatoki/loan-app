package com.mfb.lending.module.loanproduct.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "One immutable product version once it leaves draft, including fees, documents, and rules.")
public record LoanProductVersionResponse(
        UUID id,
        int versionNumber,
        String status,
        String productName,
        String description,
        String customerSegment,
        String currencyCode,
        BigDecimal minimumAmount,
        BigDecimal maximumAmount,
        int minimumTenure,
        int maximumTenure,
        String tenureUnit,
        BigDecimal interestRate,
        String interestMethod,
        String interestPeriod,
        String repaymentFrequency,
        String dayCountConvention,
        int roundingPrecision,
        String roundingMethod,
        int gracePeriodDays,
        String earlyRepaymentRule,
        BigDecimal earlyRepaymentFeeRate,
        boolean guarantorRequired,
        int minimumGuarantors,
        String guarantorNotes,
        boolean creditBureauRequired,
        Integer minimumBureauScore,
        String penaltyBasis,
        BigDecimal penaltyRate,
        BigDecimal penaltyAmount,
        int penaltyGraceDays,
        String changeReason,
        Instant effectiveFrom,
        Instant effectiveTo,
        String submittedBy,
        Instant submittedAt,
        String approvedBy,
        Instant approvedAt,
        String activatedBy,
        Instant activatedAt,
        List<FeeResponse> fees,
        List<DocumentResponse> documents,
        List<EligibilityResponse> eligibilityRules,
        List<CollateralResponse> collateralRules,
        List<ApprovalResponse> approvalRules
) {

    public record FeeResponse(
            UUID id,
            String feeType,
            String name,
            String calculation,
            BigDecimal amount,
            BigDecimal rate
    ) {
    }

    public record DocumentResponse(
            UUID id,
            String documentCode,
            String documentName,
            boolean required
    ) {
    }

    public record EligibilityResponse(UUID id, String ruleCode, String description) {
    }

    public record CollateralResponse(
            UUID id,
            String collateralType,
            String description,
            boolean mandatory,
            BigDecimal minimumCoveragePercent
    ) {
    }

    public record ApprovalResponse(UUID id, int stepOrder, String roleCode, String description) {
    }
}
