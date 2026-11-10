package com.mfb.lending.module.loanproduct.dto;

import java.math.BigDecimal;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@Schema(description = "Commercial terms stored on one loan product version. A later version does not change an earlier one.")
public record CommercialTermsRequest(
        @NotBlank @Size(max = 160) String productName,
        @Size(max = 2000) String description,
        @NotBlank @Schema(description = "INDIVIDUAL, SME, or BOTH") String customerSegment,
        @NotNull @DecimalMin("0.00") @DecimalMax("9999999999999.99") BigDecimal minimumAmount,
        @NotNull @DecimalMin("0.01") @DecimalMax("9999999999999.99") BigDecimal maximumAmount,
        @NotNull @Min(1) @Max(6000) Integer minimumTenure,
        @NotNull @Min(1) @Max(6000) Integer maximumTenure,
        @NotBlank @Schema(description = "DAYS, WEEKS, or MONTHS") String tenureUnit,
        @NotNull @DecimalMin("0.0000") @DecimalMax("100.0000") BigDecimal interestRate,
        @NotBlank @Schema(description = "FLAT or REDUCING_BALANCE") String interestMethod,
        @NotBlank @Schema(description = "ANNUAL or MONTHLY") String interestPeriod,
        @NotBlank @Schema(description = "DAILY, WEEKLY, or MONTHLY") String repaymentFrequency,
        @NotBlank @Schema(description = "ACTUAL_365, ACTUAL_360, or THIRTY_360") String dayCountConvention,
        @NotNull @Min(0) @Max(6) Integer roundingPrecision,
        @NotBlank @Schema(description = "HALF_UP or HALF_EVEN") String roundingMethod,
        @NotNull @Min(0) @Max(3660) Integer gracePeriodDays,
        @NotBlank @Schema(description = "ALLOWED, ALLOWED_WITH_FEE, or NOT_ALLOWED") String earlyRepaymentRule,
        @DecimalMin("0.0000") @DecimalMax("100.0000") BigDecimal earlyRepaymentFeeRate,
        @NotNull Boolean guarantorRequired,
        @NotNull @Min(0) @Max(20) Integer minimumGuarantors,
        @Size(max = 500) String guarantorNotes,
        @NotNull Boolean creditBureauRequired,
        @Min(0) @Max(900) Integer minimumBureauScore,
        @NotBlank @Schema(description = "NONE, FLAT_AMOUNT, PERCENTAGE_OF_OVERDUE, or PERCENTAGE_OF_INSTALMENT") String penaltyBasis,
        @DecimalMin("0.0000") @DecimalMax("100.0000") BigDecimal penaltyRate,
        @DecimalMin("0.00") @DecimalMax("9999999999999.99") BigDecimal penaltyAmount,
        @NotNull @Min(0) @Max(3660) Integer penaltyGraceDays,
        @Valid List<FeeRequest> fees,
        @Valid List<DocumentRequest> documents,
        @Valid List<EligibilityRequest> eligibilityRules,
        @Valid List<CollateralRequest> collateralRules,
        @Valid List<ApprovalRequest> approvalRules
) {

    @Schema(description = "Processing or management fee. Use amount for FLAT_AMOUNT and rate for PERCENTAGE.")
    public record FeeRequest(
            @NotBlank @Schema(description = "PROCESSING or MANAGEMENT") String feeType,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Schema(description = "FLAT_AMOUNT or PERCENTAGE") String calculation,
            @DecimalMin("0.00") @DecimalMax("9999999999999.99") BigDecimal amount,
            @DecimalMin("0.0000") @DecimalMax("100.0000") BigDecimal rate
    ) {
    }

    @Schema(description = "Document a customer must supply for this product.")
    public record DocumentRequest(
            @NotBlank @Size(max = 40) String documentCode,
            @NotBlank @Size(max = 160) String documentName,
            @NotNull Boolean required
    ) {
    }

    @Schema(description = "Eligibility condition evaluated before a loan is offered.")
    public record EligibilityRequest(
            @NotBlank @Size(max = 40) String ruleCode,
            @NotBlank @Size(max = 1000) String description
    ) {
    }

    @Schema(description = "Collateral required to book this product.")
    public record CollateralRequest(
            @NotBlank @Size(max = 40) String collateralType,
            @Size(max = 500) String description,
            @NotNull Boolean mandatory,
            @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal minimumCoveragePercent
    ) {
    }

    @Schema(description = "Approval step required before a loan on this product can be booked.")
    public record ApprovalRequest(
            @NotNull @Min(1) @Max(20) Integer stepOrder,
            @NotBlank @Size(max = 64) String roleCode,
            @Size(max = 500) String description
    ) {
    }
}
