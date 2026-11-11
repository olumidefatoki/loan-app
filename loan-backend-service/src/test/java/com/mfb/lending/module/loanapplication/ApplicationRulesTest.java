package com.mfb.lending.module.loanapplication;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.customer.domain.CustomerCategory;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.KycStatus;
import com.mfb.lending.module.customer.domain.RiskClassification;
import com.mfb.lending.module.loanapplication.application.ApplicationInput;
import com.mfb.lending.module.loanapplication.application.ApplicationRules;
import com.mfb.lending.module.loanapplication.application.ProductTerms;
import com.mfb.lending.module.loanapplication.domain.EmploymentStatus;
import com.mfb.lending.module.loanapplication.domain.IndividualFinancials;
import com.mfb.lending.module.loanapplication.domain.Obligation;
import com.mfb.lending.module.loanapplication.domain.SmeFinancials;
import com.mfb.lending.module.loanproduct.domain.CollateralRule;
import com.mfb.lending.module.loanproduct.domain.CustomerSegment;
import com.mfb.lending.module.loanproduct.domain.EligibilityRule;
import com.mfb.lending.module.loanproduct.domain.ProductDocumentRequirement;
import com.mfb.lending.module.loanproduct.domain.ProductStatus;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationRulesTest {

    @Test
    void individualAndSmeFormsStaySeparate() {
        assertThat(fields(CustomerKind.INDIVIDUAL, input(
                individual(), null, List.of(), List.of(), List.of(), Set.of("PAYSLIP")), true))
                .isEmpty();

        assertThat(fields(CustomerKind.INDIVIDUAL, input(
                null, sme(true, new BigDecimal("300000.00")), List.of(), List.of(), List.of(), Set.of("PAYSLIP")), true))
                .contains("smeFinancials", "individualFinancials");

        assertThat(fields(CustomerKind.SME, input(
                null, sme(true, new BigDecimal("300000.00")), List.of(), List.of(), List.of(), Set.of("PAYSLIP")), true))
                .isEmpty();

        assertThat(fields(CustomerKind.SME, input(
                individual(), null, List.of(), List.of(), List.of(), Set.of("PAYSLIP")), true))
                .contains("individualFinancials", "smeFinancials");
    }

    @Test
    void productLimitsDocumentsGuarantorsAndCollateralAreEnforcedOnSubmit() {
        assertThat(fields(CustomerKind.INDIVIDUAL, input(
                individual(), null, List.of(), List.of(), List.of(), Set.of()), true))
                .contains("documents");

        ApplicationInput tooLarge = new ApplicationInput(
                new BigDecimal("900000.00"),
                "School fees",
                36,
                RepaymentFrequency.WEEKLY,
                null,
                false,
                individual(),
                null,
                List.of(),
                List.of(),
                List.of(),
                Set.of("PAYSLIP"));
        assertThat(fields(CustomerKind.INDIVIDUAL, tooLarge, true))
                .contains("amount", "tenure", "repaymentFrequency", "disbursementAccountId");

        assertThat(fields(CustomerKind.INDIVIDUAL, input(
                individual(), null, List.of(), List.of(), List.of(new ApplicationInput.CollateralPosition("VEHICLE", new BigDecimal("1000.00"))),
                Set.of("PAYSLIP")), true))
                .contains("collaterals");

        ProductTerms guaranteed = terms(List.of(), List.of(), List.of(), true, 1);
        assertThat(ApplicationRules.validate(CustomerKind.INDIVIDUAL, guaranteed, input(
                individual(), null, List.of(), List.of(), List.of(), Set.of("PAYSLIP")), LocalDate.of(2026, 11, 10), true)
                .stream().map(violation -> violation.field()).toList())
                .contains("guarantors");
    }

    @Test
    void smeBorrowingAuthorityAndDuplicatePolicyAreChecked() {
        assertThat(fields(CustomerKind.SME, input(
                null, sme(false, new BigDecimal("1000.00")), List.of(), List.of(), List.of(), Set.of("PAYSLIP")), true))
                .contains("smeFinancials.authorisedToBorrow", "smeFinancials.authorisedBorrowingLimit");

        assertThat(ApplicationRules.maxActiveApplications(List.of())).isEqualTo(1);
        assertThat(ApplicationRules.maxActiveApplications(List.of(new EligibilityRule(
                UUID.randomUUID(), UUID.randomUUID(), "MAX_ACTIVE_APPLICATIONS", "2")))).isEqualTo(2);
        assertThat(ApplicationRules.maxActiveApplications(List.of(new EligibilityRule(
                UUID.randomUUID(), UUID.randomUUID(), "MAX_ACTIVE_APPLICATIONS", "Allow 3 concurrent applications")))).isEqualTo(3);

        assertThat(ApplicationRules.eligibility(customer(CustomerStatus.PENDING, KycStatus.NOT_STARTED, false, false)).violations())
                .extracting(violation -> violation.message())
                .anyMatch(message -> message.contains("active"))
                .anyMatch(message -> message.contains("KYC"));
        assertThat(ApplicationRules.eligibility(customer(CustomerStatus.ACTIVE, KycStatus.VERIFIED, true, false)).complianceHold())
                .isTrue();
    }

    @Test
    void anSmeCannotUseAnIndividualProduct() {
        assertThatThrownBy(() -> ApplicationRules.throwIfPresent(ApplicationRules.validate(
                CustomerKind.SME,
                terms(List.of(), List.of(), List.of(), false, 0),
                input(null, sme(true, null), List.of(), List.of(), List.of(), Set.of("PAYSLIP")),
                LocalDate.of(2026, 11, 10),
                false)))
                .isInstanceOf(ValidationException.class);
        assertThat(fields(CustomerKind.SME, input(
                null, sme(true, null), List.of(), List.of(), List.of(), Set.of("PAYSLIP")), false))
                .contains("productId");
    }

    private static List<String> fields(CustomerKind segment, ApplicationInput input, boolean submitting) {
        return ApplicationRules.validate(segment, terms(
                        List.of(new ProductDocumentRequirement(UUID.randomUUID(), UUID.randomUUID(), "PAYSLIP", "Payslip", true)),
                        List.of(new CollateralRule(UUID.randomUUID(), UUID.randomUUID(), "PROPERTY", "Property", true, new BigDecimal("100.00"))),
                        List.of(),
                        false,
                        0),
                input, LocalDate.of(2026, 11, 10), submitting)
                .stream()
                .map(violation -> violation.field())
                .toList();
    }

    private static ApplicationInput input(
            IndividualFinancials individual,
            SmeFinancials sme,
            List<Obligation> obligations,
            List<String> phones,
            List<ApplicationInput.CollateralPosition> collaterals,
            Set<String> documents) {
        return new ApplicationInput(
                new BigDecimal("25000.00"),
                "Working capital",
                6,
                RepaymentFrequency.MONTHLY,
                UUID.randomUUID(),
                true,
                individual,
                sme,
                obligations,
                phones,
                collaterals,
                documents);
    }

    private static IndividualFinancials individual() {
        return new IndividualFinancials(
                new BigDecimal("350000.00"),
                new BigDecimal("0.00"),
                new BigDecimal("120000.00"),
                EmploymentStatus.EMPLOYED,
                "Northwind Ltd",
                "Analyst",
                LocalDate.of(2020, 1, 15));
    }

    private static SmeFinancials sme(boolean authorised, BigDecimal limit) {
        return new SmeFinancials(
                new BigDecimal("12000000.00"),
                new BigDecimal("1000000.00"),
                new BigDecimal("400000.00"),
                new BigDecimal("150000.00"),
                new BigDecimal("50000.00"),
                authorised,
                "Ngozi Okeke",
                limit);
    }

    private static ProductTerms terms(
            List<ProductDocumentRequirement> documents,
            List<CollateralRule> collateral,
            List<EligibilityRule> eligibility,
            boolean guarantorRequired,
            int minimumGuarantors) {
        return new ProductTerms(
                ProductStatus.ACTIVE,
                ProductStatus.ACTIVE,
                CustomerSegment.INDIVIDUAL,
                new BigDecimal("1000.00"),
                new BigDecimal("50000.00"),
                1,
                12,
                TenureUnit.MONTHS,
                RepaymentFrequency.MONTHLY,
                guarantorRequired,
                minimumGuarantors,
                documents,
                collateral,
                eligibility);
    }

    private static CustomerRecord customer(CustomerStatus status, KycStatus kyc, boolean blacklisted, boolean hold) {
        return new CustomerRecord(
                UUID.randomUUID(),
                "CUS10000001",
                CustomerKind.INDIVIDUAL,
                status,
                CustomerCategory.RETAIL,
                "Ada Lovelace",
                "ada@example.com",
                "+2348012345678",
                UUID.randomUUID(),
                UUID.randomUUID(),
                kyc,
                RiskClassification.LOW,
                blacklisted,
                blacklisted ? "Fraud" : null,
                hold,
                hold ? "Review" : null,
                Instant.parse("2026-11-01T00:00:00Z"),
                Instant.parse("2026-11-01T00:00:00Z"),
                "test",
                "test");
    }
}
