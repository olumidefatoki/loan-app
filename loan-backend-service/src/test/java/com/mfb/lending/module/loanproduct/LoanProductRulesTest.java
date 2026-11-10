package com.mfb.lending.module.loanproduct;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.loanproduct.application.ProductConfigurationRules;
import com.mfb.lending.module.loanproduct.application.ProductWorkflow;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.ProductCategory;
import com.mfb.lending.module.loanproduct.domain.ProductStatus;
import com.mfb.lending.module.loanproduct.dto.CommercialTermsRequest;
import com.mfb.lending.module.loanproduct.dto.CommercialTermsRequest.CollateralRequest;
import com.mfb.lending.module.loanproduct.dto.CommercialTermsRequest.DocumentRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoanProductRulesTest {

    @Test
    void validFlatAndReducingTermsAreNormalised() {
        var reducing = ProductConfigurationRules.normalise(sample(
                "1000.00", "50000.00", 1, 12, "9.5", "REDUCING_BALANCE", "ALLOWED", null, List.of()),
                ProductCategory.SALARY_ADVANCE);
        assertThat(reducing.interestMethod()).isEqualTo(InterestMethod.REDUCING_BALANCE);
        assertThat(reducing.interestRate()).isEqualByComparingTo("9.5000");
        assertThat(reducing.fees()).hasSize(1);

        var flat = ProductConfigurationRules.normalise(sample(
                "1000.00", "50000.00", 1, 12, "4", "FLAT", "ALLOWED", null, List.of()),
                ProductCategory.PERSONAL);
        assertThat(flat.interestMethod()).isEqualTo(InterestMethod.FLAT);
    }

    @Test
    void amountTenureAndInterestMustSitInsideTheConfiguredRange() {
        assertThatThrownBy(() -> ProductConfigurationRules.normalise(sample(
                "5000.00", "1000.00", 1, 12, "9.5", "REDUCING_BALANCE", "ALLOWED", null, List.of()),
                ProductCategory.PERSONAL))
                .isInstanceOf(ValidationException.class)
                .satisfies(error -> assertThat(((ValidationException) error).getViolations())
                        .anyMatch(violation -> violation.field().equals("maximumAmount")));

        assertThatThrownBy(() -> ProductConfigurationRules.normalise(sample(
                "1000.00", "5000.00", 12, 3, "9.5", "REDUCING_BALANCE", "ALLOWED", null, List.of()),
                ProductCategory.PERSONAL))
                .isInstanceOf(ValidationException.class)
                .satisfies(error -> assertThat(((ValidationException) error).getViolations())
                        .anyMatch(violation -> violation.field().equals("maximumTenure")));

        assertThatThrownBy(() -> ProductConfigurationRules.normalise(sample(
                "1000.00", "5000.00", 1, 12, "-1", "REDUCING_BALANCE", "ALLOWED", null, List.of()),
                ProductCategory.PERSONAL))
                .isInstanceOf(ValidationException.class)
                .satisfies(error -> assertThat(((ValidationException) error).getViolations())
                        .anyMatch(violation -> violation.field().equals("interestRate")));

        assertThatThrownBy(() -> ProductConfigurationRules.normalise(sample(
                "1000.00", "5000.00", 1, 12, "150", "REDUCING_BALANCE", "ALLOWED", null, List.of()),
                ProductCategory.PERSONAL))
                .isInstanceOf(ValidationException.class)
                .satisfies(error -> assertThat(((ValidationException) error).getViolations())
                        .anyMatch(violation -> violation.field().equals("interestRate")));

        assertThatThrownBy(() -> ProductConfigurationRules.normalise(sample(
                "1000.00", "5000.00", 1, 12, "9.5", "COMPOUND", "ALLOWED", null, List.of()),
                ProductCategory.PERSONAL))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void securedUnsecuredGuarantorAndEarlyRepaymentRulesAreEnforced() {
        assertThatThrownBy(() -> ProductConfigurationRules.normalise(sample(
                "1000.00", "5000.00", 1, 12, "9.5", "FLAT", "ALLOWED", null, List.of()),
                ProductCategory.SECURED))
                .isInstanceOf(ValidationException.class)
                .satisfies(error -> assertThat(((ValidationException) error).getViolations())
                        .anyMatch(violation -> violation.field().equals("collateralRules")));

        assertThatThrownBy(() -> ProductConfigurationRules.normalise(sample(
                "1000.00", "5000.00", 1, 12, "9.5", "FLAT", "ALLOWED", null,
                List.of(new CollateralRequest("VEHICLE", "Vehicle", true, new BigDecimal("80.00")))),
                ProductCategory.UNSECURED))
                .isInstanceOf(ValidationException.class);

        ProductConfigurationRules.normalise(sample(
                "1000.00", "5000.00", 1, 12, "9.5", "FLAT", "ALLOWED", null,
                List.of(new CollateralRequest("property", "Land", true, new BigDecimal("70")))),
                ProductCategory.SECURED);

        assertThatThrownBy(() -> ProductConfigurationRules.normalise(sample(
                "1000.00", "5000.00", 1, 12, "9.5", "FLAT", "ALLOWED_WITH_FEE", null, List.of()),
                ProductCategory.EMERGENCY))
                .isInstanceOf(ValidationException.class)
                .satisfies(error -> assertThat(((ValidationException) error).getViolations())
                        .anyMatch(violation -> violation.field().equals("earlyRepaymentFeeRate")));
    }

    @Test
    void offerAmountAndTenureMustFallWithinTheProduct() {
        ProductConfigurationRules.assertWithinConfiguredLimits(
                new BigDecimal("2500.00"), 6, new BigDecimal("1000.00"), new BigDecimal("5000.00"), 1, 12);
        assertThatThrownBy(() -> ProductConfigurationRules.assertWithinConfiguredLimits(
                new BigDecimal("900.00"), 6, new BigDecimal("1000.00"), new BigDecimal("5000.00"), 1, 12))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> ProductConfigurationRules.assertWithinConfiguredLimits(
                new BigDecimal("2500.00"), 18, new BigDecimal("1000.00"), new BigDecimal("5000.00"), 1, 12))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void onlyAnActiveVersionIsOfferedAndTheMakerCannotCheckTheirOwnChange() {
        Instant now = Instant.parse("2026-11-10T12:00:00Z");
        assertThat(ProductConfigurationRules.offeredToCustomers(
                ProductStatus.ACTIVE, ProductStatus.ACTIVE, now.minusSeconds(60), null, now)).isTrue();
        assertThat(ProductConfigurationRules.offeredToCustomers(
                ProductStatus.SUSPENDED, ProductStatus.SUSPENDED, now.minusSeconds(60), null, now)).isFalse();
        assertThat(ProductConfigurationRules.offeredToCustomers(
                ProductStatus.APPROVED, ProductStatus.APPROVED, null, null, now)).isFalse();

        ProductWorkflow.assertDistinctChecker("maker", "checker", "approve this product");
        assertThatThrownBy(() -> ProductWorkflow.assertDistinctChecker("maker", "maker", "approve this product"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("different administrator");
        ProductWorkflow.assertSubmittable(ProductStatus.DRAFT);
        assertThatThrownBy(() -> ProductWorkflow.assertApprovable(ProductStatus.DRAFT))
                .isInstanceOf(ValidationException.class);
        assertThat(ProductWorkflow.submitterMayRevise(ProductStatus.PENDING_APPROVAL, "maker", "maker")).isTrue();
    }

    @Test
    void duplicateDocumentCodesAreRejected() {
        CommercialTermsRequest request = new CommercialTermsRequest(
                "Micro loan",
                null,
                "SME",
                new BigDecimal("1000.00"),
                new BigDecimal("5000.00"),
                1,
                6,
                "MONTHS",
                new BigDecimal("12"),
                "FLAT",
                "ANNUAL",
                "MONTHLY",
                "ACTUAL_365",
                2,
                "HALF_UP",
                0,
                "NOT_ALLOWED",
                null,
                false,
                0,
                null,
                false,
                null,
                "NONE",
                new BigDecimal("0"),
                new BigDecimal("0"),
                0,
                List.of(),
                List.of(
                        new DocumentRequest("CAC", "CAC certificate", true),
                        new DocumentRequest("cac", "Duplicate", true)),
                List.of(),
                List.of(),
                List.of());
        assertThatThrownBy(() -> ProductConfigurationRules.normalise(request, ProductCategory.MICROENTERPRISE))
                .isInstanceOf(ValidationException.class);
    }

    private static CommercialTermsRequest sample(
            String minimum,
            String maximum,
            int minimumTenure,
            int maximumTenure,
            String interest,
            String interestMethod,
            String earlyRule,
            BigDecimal earlyFee,
            List<CollateralRequest> collateral) {
        return new CommercialTermsRequest(
                "Salary advance",
                "For salaried customers",
                "INDIVIDUAL",
                new BigDecimal(minimum),
                new BigDecimal(maximum),
                minimumTenure,
                maximumTenure,
                "MONTHS",
                new BigDecimal(interest),
                interestMethod,
                "ANNUAL",
                "MONTHLY",
                "ACTUAL_365",
                2,
                "HALF_UP",
                5,
                earlyRule,
                earlyFee,
                false,
                0,
                null,
                true,
                500,
                "PERCENTAGE_OF_OVERDUE",
                new BigDecimal("2"),
                new BigDecimal("0"),
                3,
                List.of(new CommercialTermsRequest.FeeRequest(
                        "PROCESSING", "Processing fee", "PERCENTAGE", null, new BigDecimal("1.5"))),
                List.of(new DocumentRequest("PAYSLIP", "Payslip", true)),
                List.of(new CommercialTermsRequest.EligibilityRequest("EMPLOYED", "Applicant is employed")),
                collateral,
                List.of(new CommercialTermsRequest.ApprovalRequest(1, "CREDIT_OFFICER", "Credit approval")));
    }
}
