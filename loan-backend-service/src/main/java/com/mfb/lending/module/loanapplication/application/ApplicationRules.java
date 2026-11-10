package com.mfb.lending.module.loanapplication.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mfb.lending.common.api.FieldViolation;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.KycStatus;
import com.mfb.lending.module.loanapplication.domain.EmploymentStatus;
import com.mfb.lending.module.loanapplication.domain.IndividualFinancials;
import com.mfb.lending.module.loanapplication.domain.Obligation;
import com.mfb.lending.module.loanapplication.domain.SmeFinancials;
import com.mfb.lending.module.loanproduct.domain.CollateralRule;
import com.mfb.lending.module.loanproduct.domain.CustomerSegment;
import com.mfb.lending.module.loanproduct.domain.EligibilityRule;
import com.mfb.lending.module.loanproduct.domain.ProductDocumentRequirement;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;

public final class ApplicationRules {

    public static final String MAX_ACTIVE_APPLICATIONS = "MAX_ACTIVE_APPLICATIONS";
    private static final Pattern NUMBER = Pattern.compile("\\b([1-9]\\d{0,2})\\b");
    private static final int MAX_PARTIES = 20;

    private ApplicationRules() {
    }

    public static Eligibility eligibility(CustomerRecord customer) {
        if (customer.blacklisted() || customer.complianceHold()) {
            return new Eligibility(List.of(), true);
        }
        List<FieldViolation> violations = new ArrayList<>();
        if (customer.status() != CustomerStatus.ACTIVE) {
            violations.add(new FieldViolation("customerId", "Only an active customer can apply"));
        }
        if (customer.kycStatus() != KycStatus.VERIFIED) {
            violations.add(new FieldViolation("customerId", "KYC must be verified before an application can be started"));
        }
        return new Eligibility(violations, false);
    }

    public static boolean segmentMatches(CustomerKind kind, CustomerSegment segment) {
        return segment == CustomerSegment.BOTH || segment.name().equals(kind.name());
    }

    public static int maxActiveApplications(List<EligibilityRule> rules) {
        if (rules == null) {
            return 1;
        }
        for (EligibilityRule rule : rules) {
            if (MAX_ACTIVE_APPLICATIONS.equals(rule.ruleCode())) {
                return parseLimit(rule.description());
            }
        }
        return 1;
    }

    public static List<FieldViolation> validate(
            CustomerKind segment,
            ProductTerms product,
            ApplicationInput input,
            LocalDate today,
            boolean submitting) {
        List<FieldViolation> errors = new ArrayList<>();
        if (!segmentMatches(segment, product.customerSegment())) {
            errors.add(new FieldViolation("productId", "This product is not available for " + segment.name().toLowerCase(Locale.ROOT) + " customers"));
        }
        if (input.amount() != null && !within(input.amount(), product.minimumAmount(), product.maximumAmount())) {
            errors.add(new FieldViolation("amount", "Amount must be between " + product.minimumAmount().toPlainString()
                    + " and " + product.maximumAmount().toPlainString()));
        } else if (submitting && input.amount() == null) {
            errors.add(new FieldViolation("amount", "amount is required"));
        }
        if (input.tenure() != null && (input.tenure() < product.minimumTenure() || input.tenure() > product.maximumTenure())) {
            errors.add(new FieldViolation("tenure", "Tenure must be between " + product.minimumTenure()
                    + " and " + product.maximumTenure() + " " + product.tenureUnit().name().toLowerCase(Locale.ROOT)));
        } else if (submitting && input.tenure() == null) {
            errors.add(new FieldViolation("tenure", "tenure is required"));
        }
        if (input.repaymentFrequency() != null && input.repaymentFrequency() != product.repaymentFrequency()) {
            errors.add(new FieldViolation("repaymentFrequency", "This product is repaid " + product.repaymentFrequency().name()));
        } else if (submitting && input.repaymentFrequency() == null) {
            errors.add(new FieldViolation("repaymentFrequency", "repaymentFrequency is required"));
        }
        if (submitting && (input.purpose() == null || input.purpose().isBlank())) {
            errors.add(new FieldViolation("purpose", "purpose is required"));
        }
        if (input.disbursementAccountId() != null && !input.disbursementAccountVerified()) {
            errors.add(new FieldViolation("disbursementAccountId", "The disbursement account must be a verified account of this customer"));
        } else if (submitting && input.disbursementAccountId() == null) {
            errors.add(new FieldViolation("disbursementAccountId", "disbursementAccountId is required"));
        }
        if (segment == CustomerKind.INDIVIDUAL && input.smeFinancials() != null && input.smeFinancials().captured()) {
            errors.add(new FieldViolation("smeFinancials", "SME financials do not apply to an individual application"));
        }
        if (segment == CustomerKind.SME && input.individualFinancials() != null && input.individualFinancials().captured()) {
            errors.add(new FieldViolation("individualFinancials", "Individual financials do not apply to an SME application"));
        }
        if (segment == CustomerKind.INDIVIDUAL) {
            individual(errors, input.individualFinancials(), today, submitting);
        } else {
            sme(errors, input.smeFinancials(), input.amount(), submitting);
        }
        obligations(errors, input.obligations());
        guarantors(errors, product, input.guarantorPhones(), submitting);
        collateral(errors, product, input.amount(), input.collaterals(), submitting);
        if (submitting) {
            documents(errors, product.documents(), input.uploadedDocumentCodes());
        }
        return errors;
    }

    public static void throwIfPresent(List<FieldViolation> violations) {
        if (violations != null && !violations.isEmpty()) {
            throw new ValidationException(violations);
        }
    }

    public static RepaymentFrequency frequency(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return RepaymentFrequency.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field(field, "repaymentFrequency must be DAILY, WEEKLY, or MONTHLY");
        }
    }

    public static BigDecimal money(BigDecimal value, String field, boolean allowZero) {
        if (value == null) {
            return null;
        }
        BigDecimal scaled;
        try {
            scaled = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ValidationException.field(field, "Use at most two decimal places");
        }
        if (allowZero ? scaled.compareTo(BigDecimal.ZERO) < 0 : scaled.compareTo(BigDecimal.ZERO) <= 0) {
            throw ValidationException.field(field, allowZero ? "Must be zero or greater" : "Must be greater than zero");
        }
        return scaled;
    }

    public static BigDecimal signedMoney(BigDecimal value, String field) {
        if (value == null) {
            return null;
        }
        try {
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ValidationException.field(field, "Use at most two decimal places");
        }
    }

    private static void individual(List<FieldViolation> errors, IndividualFinancials financials, LocalDate today, boolean submitting) {
        if (financials == null || !financials.captured()) {
            if (submitting) {
                errors.add(new FieldViolation("individualFinancials", "Personal income, expenses, and employment are required"));
            }
            return;
        }
        if (submitting && financials.monthlyIncome() == null) {
            errors.add(new FieldViolation("individualFinancials.monthlyIncome", "monthlyIncome is required"));
        }
        if (submitting && financials.monthlyExpenses() == null) {
            errors.add(new FieldViolation("individualFinancials.monthlyExpenses", "monthlyExpenses is required"));
        }
        if (submitting && financials.employmentStatus() == null) {
            errors.add(new FieldViolation("individualFinancials.employmentStatus", "employmentStatus is required"));
        }
        if (financials.employmentStatus() != null && financials.employmentStatus().requiresEmployer()
                && (financials.employerName() == null || financials.employerName().isBlank())
                && submitting) {
            errors.add(new FieldViolation("individualFinancials.employerName", "employerName is required"));
        }
        if (financials.employmentStartDate() != null && financials.employmentStartDate().isAfter(today)) {
            errors.add(new FieldViolation("individualFinancials.employmentStartDate", "employmentStartDate cannot be in the future"));
        }
    }

    private static void sme(List<FieldViolation> errors, SmeFinancials financials, BigDecimal amount, boolean submitting) {
        if (financials == null || !financials.captured()) {
            if (submitting) {
                errors.add(new FieldViolation("smeFinancials", "Business turnover, expenses, cash flow, liabilities, and borrowing authority are required"));
            }
            return;
        }
        if (submitting && financials.annualTurnover() == null && financials.monthlyTurnover() == null) {
            errors.add(new FieldViolation("smeFinancials.monthlyTurnover", "monthlyTurnover or annualTurnover is required"));
        }
        if (submitting && financials.operatingExpenses() == null) {
            errors.add(new FieldViolation("smeFinancials.operatingExpenses", "operatingExpenses is required"));
        }
        if (submitting && financials.monthlyCashFlow() == null) {
            errors.add(new FieldViolation("smeFinancials.monthlyCashFlow", "monthlyCashFlow is required"));
        }
        if (submitting && financials.businessLiabilities() == null) {
            errors.add(new FieldViolation("smeFinancials.businessLiabilities", "businessLiabilities is required"));
        }
        if (submitting && !Boolean.TRUE.equals(financials.authorisedToBorrow())) {
            errors.add(new FieldViolation("smeFinancials.authorisedToBorrow", "An authorised person must approve this borrowing"));
        }
        if (submitting && (financials.authorisedBorrowerName() == null || financials.authorisedBorrowerName().isBlank())) {
            errors.add(new FieldViolation("smeFinancials.authorisedBorrowerName", "authorisedBorrowerName is required"));
        }
        if (financials.authorisedBorrowingLimit() != null && amount != null
                && financials.authorisedBorrowingLimit().compareTo(amount) < 0) {
            errors.add(new FieldViolation("smeFinancials.authorisedBorrowingLimit", "The authorised borrowing limit is below the requested amount"));
        }
    }

    private static void obligations(List<FieldViolation> errors, List<Obligation> obligations) {
        if (obligations.size() > MAX_PARTIES) {
            errors.add(new FieldViolation("obligations", "At most 20 existing obligations can be declared"));
        }
        for (int i = 0; i < obligations.size(); i++) {
            Obligation obligation = obligations.get(i);
            if (obligation.lender() == null || obligation.lender().isBlank()) {
                errors.add(new FieldViolation("obligations[" + i + "].lender", "lender is required"));
            }
            if (obligation.outstandingBalance() == null || obligation.outstandingBalance().compareTo(BigDecimal.ZERO) < 0) {
                errors.add(new FieldViolation("obligations[" + i + "].outstandingBalance", "outstandingBalance must be zero or greater"));
            }
            if (obligation.monthlyRepayment() == null || obligation.monthlyRepayment().compareTo(BigDecimal.ZERO) < 0) {
                errors.add(new FieldViolation("obligations[" + i + "].monthlyRepayment", "monthlyRepayment must be zero or greater"));
            }
        }
    }

    private static void guarantors(List<FieldViolation> errors, ProductTerms product, List<String> phones, boolean submitting) {
        if (phones.size() > MAX_PARTIES) {
            errors.add(new FieldViolation("guarantors", "At most 20 guarantors can be declared"));
        }
        Set<String> seen = new HashSet<>();
        for (String phone : phones) {
            if (!seen.add(phone)) {
                errors.add(new FieldViolation("guarantors", "Each guarantor must have a distinct phone number"));
                break;
            }
        }
        if (submitting && product.guarantorRequired() && phones.size() < product.minimumGuarantors()) {
            errors.add(new FieldViolation("guarantors", "At least " + product.minimumGuarantors() + " guarantor is required"));
        }
    }

    private static void collateral(
            List<FieldViolation> errors,
            ProductTerms product,
            BigDecimal amount,
            List<ApplicationInput.CollateralPosition> declared,
            boolean submitting) {
        if (declared.size() > MAX_PARTIES) {
            errors.add(new FieldViolation("collaterals", "At most 20 collateral items can be declared"));
        }
        Set<String> allowed = new HashSet<>();
        for (CollateralRule rule : product.collateralRules()) {
            allowed.add(rule.collateralType());
        }
        if (!allowed.isEmpty()) {
            for (int i = 0; i < declared.size(); i++) {
                if (!allowed.contains(declared.get(i).type())) {
                    errors.add(new FieldViolation("collaterals[" + i + "].collateralType",
                            "This product does not accept " + declared.get(i).type()));
                }
            }
        }
        if (!submitting || amount == null) {
            return;
        }
        for (CollateralRule rule : product.collateralRules()) {
            if (!rule.mandatory()) {
                continue;
            }
            BigDecimal covered = BigDecimal.ZERO;
            for (ApplicationInput.CollateralPosition item : declared) {
                if (rule.collateralType().equals(item.type()) && item.estimatedValue() != null) {
                    covered = covered.add(item.estimatedValue());
                }
            }
            BigDecimal required = amount.multiply(rule.minimumCoveragePercent())
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            if (covered.compareTo(required) < 0) {
                errors.add(new FieldViolation("collaterals", rule.collateralType()
                        + " must cover at least " + rule.minimumCoveragePercent().toPlainString() + "% of the loan amount"));
            }
        }
    }

    private static void documents(List<FieldViolation> errors, List<ProductDocumentRequirement> required, Set<String> uploaded) {
        for (ProductDocumentRequirement document : required) {
            if (document.required() && !uploaded.contains(document.documentCode())) {
                errors.add(new FieldViolation("documents", document.documentCode() + " is required"));
            }
        }
    }

    private static boolean within(BigDecimal amount, BigDecimal minimum, BigDecimal maximum) {
        return amount.compareTo(minimum) >= 0 && amount.compareTo(maximum) <= 0;
    }

    private static int parseLimit(String description) {
        if (description == null || description.isBlank()) {
            return 1;
        }
        String trimmed = description.trim();
        if (trimmed.matches("[1-9]\\d{0,2}")) {
            return Integer.parseInt(trimmed);
        }
        Matcher matcher = NUMBER.matcher(trimmed);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        return 1;
    }

    public record Eligibility(List<FieldViolation> violations, boolean complianceHold) {
    }
}
