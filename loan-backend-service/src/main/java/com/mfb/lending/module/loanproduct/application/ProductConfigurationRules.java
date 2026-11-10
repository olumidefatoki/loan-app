package com.mfb.lending.module.loanproduct.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.mfb.lending.common.api.FieldViolation;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.loanproduct.domain.ApprovalRule;
import com.mfb.lending.module.loanproduct.domain.CollateralRule;
import com.mfb.lending.module.loanproduct.domain.CustomerSegment;
import com.mfb.lending.module.loanproduct.domain.DayCountConvention;
import com.mfb.lending.module.loanproduct.domain.EarlyRepaymentRule;
import com.mfb.lending.module.loanproduct.domain.EligibilityRule;
import com.mfb.lending.module.loanproduct.domain.FeeCalculation;
import com.mfb.lending.module.loanproduct.domain.FeeType;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.InterestPeriod;
import com.mfb.lending.module.loanproduct.domain.PenaltyBasis;
import com.mfb.lending.module.loanproduct.domain.ProductCategory;
import com.mfb.lending.module.loanproduct.domain.ProductConfiguration;
import com.mfb.lending.module.loanproduct.domain.ProductDocumentRequirement;
import com.mfb.lending.module.loanproduct.domain.ProductFee;
import com.mfb.lending.module.loanproduct.domain.ProductStatus;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.RoundingMethod;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;
import com.mfb.lending.module.loanproduct.dto.CommercialTermsRequest;

public final class ProductConfigurationRules {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final Pattern ROLE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal ZERO_RATE = BigDecimal.ZERO.setScale(4);
    private static final int MAX_COMPONENTS = 30;

    private ProductConfigurationRules() {
    }

    public static ProductConfiguration normalise(CommercialTermsRequest request, ProductCategory category) {
        List<FieldViolation> violations = new ArrayList<>();
        String name = text(request.productName(), "productName", 160, true, violations);
        String description = text(request.description(), "description", 2000, false, violations);
        CustomerSegment segment = enumeration(CustomerSegment.class, request.customerSegment(), "customerSegment", violations);
        BigDecimal minimumAmount = money(request.minimumAmount(), "minimumAmount", true, violations);
        BigDecimal maximumAmount = money(request.maximumAmount(), "maximumAmount", true, violations);
        if (minimumAmount != null && maximumAmount != null && maximumAmount.compareTo(minimumAmount) < 0) {
            violations.add(new FieldViolation("maximumAmount", "Maximum amount must be greater than or equal to minimum amount"));
        }
        if (maximumAmount != null && maximumAmount.compareTo(BigDecimal.ZERO) <= 0) {
            violations.add(new FieldViolation("maximumAmount", "Maximum amount must be greater than zero"));
        }
        Integer minimumTenure = request.minimumTenure();
        Integer maximumTenure = request.maximumTenure();
        if (minimumTenure != null && maximumTenure != null && maximumTenure < minimumTenure) {
            violations.add(new FieldViolation("maximumTenure", "Maximum tenure must be greater than or equal to minimum tenure"));
        }
        TenureUnit tenureUnit = enumeration(TenureUnit.class, request.tenureUnit(), "tenureUnit", violations);
        BigDecimal interestRate = rate(request.interestRate(), "interestRate", true, violations);
        if (interestRate != null && (interestRate.compareTo(BigDecimal.ZERO) < 0 || interestRate.compareTo(HUNDRED) > 0)) {
            violations.add(new FieldViolation("interestRate", "Interest rate must be between 0 and 100"));
        }
        InterestMethod interestMethod = enumeration(InterestMethod.class, request.interestMethod(), "interestMethod", violations);
        InterestPeriod interestPeriod = enumeration(InterestPeriod.class, request.interestPeriod(), "interestPeriod", violations);
        RepaymentFrequency frequency = enumeration(RepaymentFrequency.class, request.repaymentFrequency(), "repaymentFrequency", violations);
        DayCountConvention dayCount = enumeration(DayCountConvention.class, request.dayCountConvention(), "dayCountConvention", violations);
        RoundingMethod roundingMethod = enumeration(RoundingMethod.class, request.roundingMethod(), "roundingMethod", violations);
        Integer roundingPrecision = request.roundingPrecision();
        Integer gracePeriodDays = request.gracePeriodDays();
        if (gracePeriodDays != null && minimumTenure != null && maximumTenure != null && tenureUnit != null
                && gracePeriodDays > tenureDays(maximumTenure, tenureUnit)) {
            violations.add(new FieldViolation("gracePeriodDays", "Grace period must fall within the maximum tenure"));
        }
        EarlyRepaymentRule earlyRule = enumeration(EarlyRepaymentRule.class, request.earlyRepaymentRule(), "earlyRepaymentRule", violations);
        BigDecimal earlyFee = rate(request.earlyRepaymentFeeRate(), "earlyRepaymentFeeRate", false, violations);
        if (earlyRule == EarlyRepaymentRule.ALLOWED_WITH_FEE && earlyFee == null) {
            violations.add(new FieldViolation("earlyRepaymentFeeRate", "Early repayment fee rate is required when a fee applies"));
        }
        if (earlyRule != null && earlyRule != EarlyRepaymentRule.ALLOWED_WITH_FEE && earlyFee != null) {
            violations.add(new FieldViolation("earlyRepaymentFeeRate", "Early repayment fee rate applies only when the rule is ALLOWED_WITH_FEE"));
        }
        if (earlyFee != null && (earlyFee.compareTo(BigDecimal.ZERO) < 0 || earlyFee.compareTo(HUNDRED) > 0)) {
            violations.add(new FieldViolation("earlyRepaymentFeeRate", "Early repayment fee rate must be between 0 and 100"));
        }
        Boolean guarantorRequired = request.guarantorRequired();
        Integer minimumGuarantors = request.minimumGuarantors();
        if (guarantorRequired != null && minimumGuarantors != null) {
            if (guarantorRequired && minimumGuarantors < 1) {
                violations.add(new FieldViolation("minimumGuarantors", "At least one guarantor is required"));
            }
            if (!guarantorRequired && minimumGuarantors != 0) {
                violations.add(new FieldViolation("minimumGuarantors", "Minimum guarantors must be zero when a guarantor is not required"));
            }
        }
        String guarantorNotes = text(request.guarantorNotes(), "guarantorNotes", 500, false, violations);
        Boolean bureauRequired = request.creditBureauRequired();
        Integer bureauScore = request.minimumBureauScore();
        if (bureauRequired != null && !bureauRequired && bureauScore != null) {
            violations.add(new FieldViolation("minimumBureauScore", "Minimum bureau score applies only when a credit bureau check is required"));
        }
        PenaltyBasis penaltyBasis = enumeration(PenaltyBasis.class, request.penaltyBasis(), "penaltyBasis", violations);
        BigDecimal penaltyRate = rate(request.penaltyRate(), "penaltyRate", false, violations);
        BigDecimal penaltyAmount = money(request.penaltyAmount(), "penaltyAmount", false, violations);
        if (penaltyBasis != null) {
            penaltyRate = penaltyRate == null ? ZERO_RATE : penaltyRate;
            penaltyAmount = penaltyAmount == null ? ZERO : penaltyAmount;
            applyPenalty(penaltyBasis, penaltyRate, penaltyAmount, violations);
        }
        Integer penaltyGrace = request.penaltyGraceDays();
        if (penaltyGrace != null && minimumTenure != null && maximumTenure != null && tenureUnit != null
                && penaltyGrace > tenureDays(maximumTenure, tenureUnit)) {
            violations.add(new FieldViolation("penaltyGraceDays", "Penalty grace period must fall within the maximum tenure"));
        }

        List<ProductFee> fees = fees(request.fees(), violations);
        List<ProductDocumentRequirement> documents = documents(request.documents(), violations);
        List<EligibilityRule> eligibility = eligibility(request.eligibilityRules(), violations);
        List<CollateralRule> collateral = collateral(request.collateralRules(), category, violations);
        List<ApprovalRule> approvals = approvals(request.approvalRules(), violations);

        if (!violations.isEmpty() || missing(name, segment, minimumAmount, maximumAmount, minimumTenure, maximumTenure,
                tenureUnit, interestRate, interestMethod, interestPeriod, frequency, dayCount, roundingMethod,
                roundingPrecision, gracePeriodDays, earlyRule, guarantorRequired, minimumGuarantors, bureauRequired,
                penaltyBasis, penaltyGrace)) {
            throw new ValidationException(violations);
        }
        return new ProductConfiguration(
                name,
                description,
                segment,
                minimumAmount,
                maximumAmount,
                minimumTenure,
                maximumTenure,
                tenureUnit,
                interestRate,
                interestMethod,
                interestPeriod,
                frequency,
                dayCount,
                roundingPrecision,
                roundingMethod,
                gracePeriodDays,
                earlyRule,
                earlyRule == EarlyRepaymentRule.ALLOWED_WITH_FEE ? earlyFee : null,
                guarantorRequired,
                minimumGuarantors,
                guarantorNotes,
                bureauRequired,
                bureauRequired ? bureauScore : null,
                penaltyBasis,
                penaltyBasis == PenaltyBasis.FLAT_AMOUNT || penaltyBasis == PenaltyBasis.NONE ? ZERO_RATE : penaltyRate,
                penaltyBasis == PenaltyBasis.FLAT_AMOUNT ? penaltyAmount : ZERO,
                penaltyGrace,
                fees,
                documents,
                eligibility,
                collateral,
                approvals);
    }

    public static void assertWithinConfiguredLimits(
            BigDecimal amount,
            int tenure,
            BigDecimal minimumAmount,
            BigDecimal maximumAmount,
            int minimumTenure,
            int maximumTenure) {
        List<FieldViolation> violations = new ArrayList<>();
        if (amount == null || amount.compareTo(minimumAmount) < 0 || amount.compareTo(maximumAmount) > 0) {
            violations.add(new FieldViolation("amount", "Amount must fall within the product limits"));
        }
        if (tenure < minimumTenure || tenure > maximumTenure) {
            violations.add(new FieldViolation("tenure", "Tenure must fall within the product limits"));
        }
        if (!violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }

    public static boolean offeredToCustomers(
            ProductStatus productStatus,
            ProductStatus versionStatus,
            Instant effectiveFrom,
            Instant effectiveTo,
            Instant now) {
        return productStatus == ProductStatus.ACTIVE
                && versionStatus == ProductStatus.ACTIVE
                && effectiveFrom != null
                && !effectiveFrom.isAfter(now)
                && (effectiveTo == null || effectiveTo.isAfter(now));
    }

    private static void applyPenalty(
            PenaltyBasis basis,
            BigDecimal penaltyRate,
            BigDecimal penaltyAmount,
            List<FieldViolation> violations) {
        if (basis == PenaltyBasis.NONE && (penaltyRate.compareTo(BigDecimal.ZERO) != 0 || penaltyAmount.compareTo(BigDecimal.ZERO) != 0)) {
            violations.add(new FieldViolation("penaltyBasis", "A product without a penalty must have a zero penalty rate and amount"));
        }
        if (basis == PenaltyBasis.FLAT_AMOUNT) {
            if (penaltyAmount.compareTo(BigDecimal.ZERO) <= 0) {
                violations.add(new FieldViolation("penaltyAmount", "Flat penalty amount must be greater than zero"));
            }
            if (penaltyRate.compareTo(BigDecimal.ZERO) != 0) {
                violations.add(new FieldViolation("penaltyRate", "Flat penalty uses penalty amount"));
            }
        }
        if (basis == PenaltyBasis.PERCENTAGE_OF_OVERDUE || basis == PenaltyBasis.PERCENTAGE_OF_INSTALMENT) {
            if (penaltyRate.compareTo(BigDecimal.ZERO) <= 0 || penaltyRate.compareTo(HUNDRED) > 0) {
                violations.add(new FieldViolation("penaltyRate", "Penalty rate must be greater than 0 and at most 100"));
            }
            if (penaltyAmount.compareTo(BigDecimal.ZERO) != 0) {
                violations.add(new FieldViolation("penaltyAmount", "Percentage penalty uses penalty rate"));
            }
        }
    }

    private static List<ProductFee> fees(List<CommercialTermsRequest.FeeRequest> requests, List<FieldViolation> violations) {
        List<CommercialTermsRequest.FeeRequest> source = requests == null ? List.of() : requests;
        if (source.size() > MAX_COMPONENTS) {
            violations.add(new FieldViolation("fees", "A product version accepts at most 30 fees"));
            return List.of();
        }
        Set<String> names = new HashSet<>();
        List<ProductFee> fees = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            CommercialTermsRequest.FeeRequest request = source.get(i);
            String prefix = "fees[" + i + "].";
            FeeType type = enumeration(FeeType.class, request.feeType(), prefix + "feeType", violations);
            String name = text(request.name(), prefix + "name", 120, true, violations);
            FeeCalculation calculation = enumeration(FeeCalculation.class, request.calculation(), prefix + "calculation", violations);
            BigDecimal amount = money(request.amount(), prefix + "amount", false, violations);
            BigDecimal feeRate = rate(request.rate(), prefix + "rate", false, violations);
            if (name != null && !names.add(name.toLowerCase(Locale.ROOT))) {
                violations.add(new FieldViolation(prefix + "name", "Fee name must be unique"));
            }
            if (calculation == FeeCalculation.FLAT_AMOUNT) {
                if (amount == null || amount.compareTo(BigDecimal.ZERO) < 0) {
                    violations.add(new FieldViolation(prefix + "amount", "Flat fee amount must be zero or greater"));
                }
                if (feeRate != null) {
                    violations.add(new FieldViolation(prefix + "rate", "Flat fee uses amount"));
                }
            }
            if (calculation == FeeCalculation.PERCENTAGE) {
                if (feeRate == null || feeRate.compareTo(BigDecimal.ZERO) < 0 || feeRate.compareTo(HUNDRED) > 0) {
                    violations.add(new FieldViolation(prefix + "rate", "Percentage fee rate must be between 0 and 100"));
                }
                if (amount != null) {
                    violations.add(new FieldViolation(prefix + "amount", "Percentage fee uses rate"));
                }
            }
            if (type != null && name != null && calculation != null) {
                fees.add(new ProductFee(
                        null,
                        null,
                        type,
                        name,
                        calculation,
                        calculation == FeeCalculation.FLAT_AMOUNT ? amount : null,
                        calculation == FeeCalculation.PERCENTAGE ? feeRate : null));
            }
        }
        return List.copyOf(fees);
    }

    private static List<ProductDocumentRequirement> documents(
            List<CommercialTermsRequest.DocumentRequest> requests,
            List<FieldViolation> violations) {
        List<CommercialTermsRequest.DocumentRequest> source = requests == null ? List.of() : requests;
        if (source.size() > MAX_COMPONENTS) {
            violations.add(new FieldViolation("documents", "A product version accepts at most 30 documents"));
            return List.of();
        }
        Set<String> codes = new HashSet<>();
        List<ProductDocumentRequirement> documents = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            CommercialTermsRequest.DocumentRequest request = source.get(i);
            String prefix = "documents[" + i + "].";
            String code = code(request.documentCode(), prefix + "documentCode", CODE, violations);
            String name = text(request.documentName(), prefix + "documentName", 160, true, violations);
            if (request.required() == null) {
                violations.add(new FieldViolation(prefix + "required", "required is required"));
            }
            if (code != null && !codes.add(code)) {
                violations.add(new FieldViolation(prefix + "documentCode", "Document code must be unique"));
            }
            if (code != null && name != null && request.required() != null) {
                documents.add(new ProductDocumentRequirement(null, null, code, name, request.required()));
            }
        }
        return List.copyOf(documents);
    }

    private static List<EligibilityRule> eligibility(
            List<CommercialTermsRequest.EligibilityRequest> requests,
            List<FieldViolation> violations) {
        List<CommercialTermsRequest.EligibilityRequest> source = requests == null ? List.of() : requests;
        if (source.size() > MAX_COMPONENTS) {
            violations.add(new FieldViolation("eligibilityRules", "A product version accepts at most 30 eligibility rules"));
            return List.of();
        }
        Set<String> codes = new HashSet<>();
        List<EligibilityRule> rules = new ArrayList<>();
        for (int i = 0; i < source.size(); i++) {
            CommercialTermsRequest.EligibilityRequest request = source.get(i);
            String prefix = "eligibilityRules[" + i + "].";
            String code = code(request.ruleCode(), prefix + "ruleCode", CODE, violations);
            String description = text(request.description(), prefix + "description", 1000, true, violations);
            if (code != null && !codes.add(code)) {
                violations.add(new FieldViolation(prefix + "ruleCode", "Eligibility rule code must be unique"));
            }
            if (code != null && description != null) {
                rules.add(new EligibilityRule(null, null, code, description));
            }
        }
        return List.copyOf(rules);
    }

    private static List<CollateralRule> collateral(
            List<CommercialTermsRequest.CollateralRequest> requests,
            ProductCategory category,
            List<FieldViolation> violations) {
        List<CommercialTermsRequest.CollateralRequest> source = requests == null ? List.of() : requests;
        if (source.size() > MAX_COMPONENTS) {
            violations.add(new FieldViolation("collateralRules", "A product version accepts at most 30 collateral rules"));
            return List.of();
        }
        Set<String> types = new HashSet<>();
        List<CollateralRule> rules = new ArrayList<>();
        boolean mandatory = false;
        for (int i = 0; i < source.size(); i++) {
            CommercialTermsRequest.CollateralRequest request = source.get(i);
            String prefix = "collateralRules[" + i + "].";
            String type = code(request.collateralType(), prefix + "collateralType", CODE, violations);
            String description = text(request.description(), prefix + "description", 500, false, violations);
            BigDecimal coverage = money(request.minimumCoveragePercent(), prefix + "minimumCoveragePercent", false, violations);
            if (request.mandatory() == null) {
                violations.add(new FieldViolation(prefix + "mandatory", "mandatory is required"));
            }
            if (type != null && !types.add(type)) {
                violations.add(new FieldViolation(prefix + "collateralType", "Collateral type must be unique"));
            }
            boolean required = Boolean.TRUE.equals(request.mandatory());
            if (required) {
                mandatory = true;
                if (coverage == null || coverage.compareTo(BigDecimal.ZERO) <= 0 || coverage.compareTo(HUNDRED) > 0) {
                    violations.add(new FieldViolation(prefix + "minimumCoveragePercent", "Mandatory collateral needs a coverage percent from 0.01 to 100"));
                }
            } else if (coverage != null && (coverage.compareTo(BigDecimal.ZERO) < 0 || coverage.compareTo(HUNDRED) > 0)) {
                violations.add(new FieldViolation(prefix + "minimumCoveragePercent", "Coverage percent must be between 0 and 100"));
            }
            if (type != null && request.mandatory() != null) {
                rules.add(new CollateralRule(null, null, type, description, required, coverage));
            }
        }
        if (category == ProductCategory.SECURED && !mandatory) {
            violations.add(new FieldViolation("collateralRules", "A secured product needs at least one mandatory collateral rule"));
        }
        if (category == ProductCategory.UNSECURED && !source.isEmpty()) {
            violations.add(new FieldViolation("collateralRules", "An unsecured product has no collateral rules"));
        }
        return List.copyOf(rules);
    }

    private static List<ApprovalRule> approvals(
            List<CommercialTermsRequest.ApprovalRequest> requests,
            List<FieldViolation> violations) {
        List<CommercialTermsRequest.ApprovalRequest> source = requests == null ? List.of() : requests;
        if (source.size() > 20) {
            violations.add(new FieldViolation("approvalRules", "A product version accepts at most 20 approval steps"));
            return List.of();
        }
        List<ApprovalRule> rules = new ArrayList<>();
        Set<Integer> steps = new HashSet<>();
        for (int i = 0; i < source.size(); i++) {
            CommercialTermsRequest.ApprovalRequest request = source.get(i);
            String prefix = "approvalRules[" + i + "].";
            String role = code(request.roleCode(), prefix + "roleCode", ROLE, violations);
            String description = text(request.description(), prefix + "description", 500, false, violations);
            Integer step = request.stepOrder();
            if (step != null && !steps.add(step)) {
                violations.add(new FieldViolation(prefix + "stepOrder", "Approval step order must be unique"));
            }
            if (role != null && step != null) {
                rules.add(new ApprovalRule(null, null, step, role, description));
            }
        }
        List<Integer> ordered = steps.stream().sorted(Comparator.naturalOrder()).toList();
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i) != i + 1) {
                violations.add(new FieldViolation("approvalRules", "Approval steps must start at 1 and increase by one"));
                break;
            }
        }
        return List.copyOf(rules);
    }

    private static int tenureDays(int tenure, TenureUnit unit) {
        return switch (unit) {
            case DAYS -> tenure;
            case WEEKS -> Math.multiplyExact(tenure, 7);
            case MONTHS -> Math.multiplyExact(tenure, 31);
        };
    }

    private static boolean missing(Object... values) {
        for (Object value : values) {
            if (value == null) {
                return true;
            }
        }
        return false;
    }

    private static <E extends Enum<E>> E enumeration(Class<E> type, String raw, String field, List<FieldViolation> violations) {
        if (raw == null || raw.isBlank()) {
            violations.add(new FieldViolation(field, field + " is required"));
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            violations.add(new FieldViolation(field, "Unsupported " + field));
            return null;
        }
    }

    private static String text(String raw, String field, int max, boolean required, List<FieldViolation> violations) {
        if (raw == null || raw.isBlank()) {
            if (required) {
                violations.add(new FieldViolation(field, field + " is required"));
            }
            return null;
        }
        String value = raw.trim();
        if (value.length() > max) {
            violations.add(new FieldViolation(field, field + " must be at most " + max + " characters"));
            return null;
        }
        return value;
    }

    private static String code(String raw, String field, Pattern pattern, List<FieldViolation> violations) {
        String value = text(raw, field, 64, true, violations);
        if (value == null) {
            return null;
        }
        String normalised = value.toUpperCase(Locale.ROOT);
        if (!pattern.matcher(normalised).matches()) {
            violations.add(new FieldViolation(field, field + " must be an uppercase code"));
            return null;
        }
        return normalised;
    }

    private static BigDecimal money(BigDecimal value, String field, boolean required, List<FieldViolation> violations) {
        if (value == null) {
            if (required) {
                violations.add(new FieldViolation(field, field + " is required"));
            }
            return null;
        }
        if (value.scale() > 2) {
            violations.add(new FieldViolation(field, field + " must have at most 2 decimal places"));
            return null;
        }
        return value.setScale(2);
    }

    private static BigDecimal rate(BigDecimal value, String field, boolean required, List<FieldViolation> violations) {
        if (value == null) {
            if (required) {
                violations.add(new FieldViolation(field, field + " is required"));
            }
            return null;
        }
        if (value.scale() > 4) {
            violations.add(new FieldViolation(field, field + " must have at most 4 decimal places"));
            return null;
        }
        return value.setScale(4);
    }
}
