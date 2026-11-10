package com.mfb.lending.module.loanproduct.application;

import java.util.List;

import com.mfb.lending.module.loanproduct.domain.ApprovalRule;
import com.mfb.lending.module.loanproduct.domain.CollateralRule;
import com.mfb.lending.module.loanproduct.domain.EligibilityRule;
import com.mfb.lending.module.loanproduct.domain.LoanProductRecord;
import com.mfb.lending.module.loanproduct.domain.LoanProductVersionRecord;
import com.mfb.lending.module.loanproduct.domain.ProductComponents;
import com.mfb.lending.module.loanproduct.domain.ProductDocumentRequirement;
import com.mfb.lending.module.loanproduct.domain.ProductFee;
import com.mfb.lending.module.loanproduct.domain.ProductSummary;
import com.mfb.lending.module.loanproduct.dto.CalculatorConfigResponse;
import com.mfb.lending.module.loanproduct.dto.LoanProductResponse;
import com.mfb.lending.module.loanproduct.dto.LoanProductSummaryResponse;
import com.mfb.lending.module.loanproduct.dto.LoanProductVersionResponse;

public final class LoanProductViews {

    private LoanProductViews() {
    }

    public static LoanProductResponse detail(
            LoanProductRecord product,
            LoanProductVersionRecord version,
            ProductComponents components,
            LoanProductVersionRecord pending) {
        return new LoanProductResponse(
                product.id(),
                product.productCode(),
                product.category().name(),
                product.status().name(),
                version(version, components),
                pending == null ? null : pending.id(),
                pending == null ? null : pending.status().name(),
                product.createdAt(),
                product.updatedAt());
    }

    public static LoanProductSummaryResponse summary(ProductSummary product) {
        return new LoanProductSummaryResponse(
                product.id(),
                product.productCode(),
                product.productName(),
                product.category().name(),
                product.status().name(),
                product.customerSegment().name(),
                product.currencyCode(),
                product.minimumAmount(),
                product.maximumAmount(),
                product.minimumTenure(),
                product.maximumTenure(),
                product.tenureUnit().name(),
                product.interestRate(),
                product.interestMethod().name(),
                product.repaymentFrequency().name(),
                product.versionNumber());
    }

    public static LoanProductVersionResponse version(LoanProductVersionRecord version, ProductComponents components) {
        return new LoanProductVersionResponse(
                version.id(),
                version.versionNumber(),
                version.status().name(),
                version.productName(),
                version.description(),
                version.customerSegment().name(),
                version.currencyCode(),
                version.minimumAmount(),
                version.maximumAmount(),
                version.minimumTenure(),
                version.maximumTenure(),
                version.tenureUnit().name(),
                version.interestRate(),
                version.interestMethod().name(),
                version.interestPeriod().name(),
                version.repaymentFrequency().name(),
                version.dayCountConvention().name(),
                version.roundingPrecision(),
                version.roundingMethod().name(),
                version.gracePeriodDays(),
                version.earlyRepaymentRule().name(),
                version.earlyRepaymentFeeRate(),
                version.guarantorRequired(),
                version.minimumGuarantors(),
                version.guarantorNotes(),
                version.creditBureauRequired(),
                version.minimumBureauScore(),
                version.penaltyBasis().name(),
                version.penaltyRate(),
                version.penaltyAmount(),
                version.penaltyGraceDays(),
                version.changeReason(),
                version.effectiveFrom(),
                version.effectiveTo(),
                version.submittedBy(),
                version.submittedAt(),
                version.approvedBy(),
                version.approvedAt(),
                version.activatedBy(),
                version.activatedAt(),
                components.fees().stream().map(LoanProductViews::fee).toList(),
                components.documents().stream().map(LoanProductViews::document).toList(),
                components.eligibilityRules().stream().map(LoanProductViews::eligibility).toList(),
                components.collateralRules().stream().map(LoanProductViews::collateral).toList(),
                components.approvalRules().stream().map(LoanProductViews::approval).toList());
    }

    public static CalculatorConfigResponse calculator(
            LoanProductRecord product,
            LoanProductVersionRecord version,
            List<ProductFee> fees) {
        return new CalculatorConfigResponse(
                product.id(),
                version.id(),
                version.versionNumber(),
                product.productCode(),
                version.productName(),
                version.currencyCode(),
                version.minimumAmount(),
                version.maximumAmount(),
                version.minimumTenure(),
                version.maximumTenure(),
                version.tenureUnit().name(),
                version.interestRate(),
                version.interestMethod().name(),
                version.interestPeriod().name(),
                version.repaymentFrequency().name(),
                version.dayCountConvention().name(),
                version.roundingPrecision(),
                version.roundingMethod().name(),
                version.gracePeriodDays(),
                version.earlyRepaymentRule().name(),
                version.earlyRepaymentFeeRate(),
                version.penaltyBasis().name(),
                version.penaltyRate(),
                version.penaltyAmount(),
                version.penaltyGraceDays(),
                fees.stream().map(LoanProductViews::fee).toList());
    }

    private static LoanProductVersionResponse.FeeResponse fee(ProductFee fee) {
        return new LoanProductVersionResponse.FeeResponse(
                fee.id(), fee.feeType().name(), fee.name(), fee.calculation().name(), fee.amount(), fee.rate());
    }

    private static LoanProductVersionResponse.DocumentResponse document(ProductDocumentRequirement document) {
        return new LoanProductVersionResponse.DocumentResponse(
                document.id(), document.documentCode(), document.documentName(), document.required());
    }

    private static LoanProductVersionResponse.EligibilityResponse eligibility(EligibilityRule rule) {
        return new LoanProductVersionResponse.EligibilityResponse(rule.id(), rule.ruleCode(), rule.description());
    }

    private static LoanProductVersionResponse.CollateralResponse collateral(CollateralRule rule) {
        return new LoanProductVersionResponse.CollateralResponse(
                rule.id(), rule.collateralType(), rule.description(), rule.mandatory(), rule.minimumCoveragePercent());
    }

    private static LoanProductVersionResponse.ApprovalResponse approval(ApprovalRule rule) {
        return new LoanProductVersionResponse.ApprovalResponse(rule.id(), rule.stepOrder(), rule.roleCode(), rule.description());
    }
}
