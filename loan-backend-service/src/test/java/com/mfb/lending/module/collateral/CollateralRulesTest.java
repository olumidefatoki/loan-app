package com.mfb.lending.module.collateral;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.collateral.application.CollateralRules;
import com.mfb.lending.module.collateral.application.CollateralValuationCalculator;
import com.mfb.lending.module.collateral.domain.CheckStatus;
import com.mfb.lending.module.collateral.domain.CollateralType;
import com.mfb.lending.module.collateral.domain.EncumbranceStatus;
import com.mfb.lending.module.collateral.domain.InsuranceCheckStatus;
import com.mfb.lending.module.collateral.domain.ValuationStatus;
import com.mfb.lending.module.collateral.domain.VerificationStatus;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollateralRulesTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 11);

    @Test
    void eligibleValueUsesTheLowerOfForcedSaleAndThePolicyRatio() {
        CollateralValuationCalculator.EligibleValue value = CollateralValuationCalculator.value(
                CollateralType.RESIDENTIAL_PROPERTY,
                new BigDecimal("1000000.00"),
                new BigDecimal("800000.00"),
                null,
                null);
        assertThat(value.lendingRatio()).isEqualByComparingTo("0.700000");
        assertThat(value.calculatedEligibleValue()).isEqualByComparingTo("700000.00");
        assertThat(value.acceptedEligibleValue()).isEqualByComparingTo("700000.00");
    }

    @Test
    void statedEligibleValueCannotExceedTheCalculatedValue() {
        CollateralValuationCalculator.EligibleValue accepted = CollateralValuationCalculator.value(
                CollateralType.VEHICLE,
                new BigDecimal("100.01"),
                new BigDecimal("70.00"),
                new BigDecimal("50.00"),
                new BigDecimal("0.50"));
        assertThat(accepted.calculatedEligibleValue()).isEqualByComparingTo("50.01");
        assertThat(accepted.acceptedEligibleValue()).isEqualByComparingTo("50.00");
        assertThatThrownBy(() -> CollateralValuationCalculator.value(
                CollateralType.VEHICLE,
                new BigDecimal("100.01"),
                new BigDecimal("80.00"),
                new BigDecimal("60.00"),
                new BigDecimal("0.50")))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> CollateralValuationCalculator.value(
                CollateralType.LAND,
                new BigDecimal("100.00"),
                new BigDecimal("120.00"),
                null,
                null))
                .isInstanceOf(ValidationException.class);

        assertThatThrownBy(() -> CollateralValuationCalculator.value(
                CollateralType.RESIDENTIAL_PROPERTY,
                new BigDecimal("1000000"),
                new BigDecimal("800000"),
                null,
                new BigDecimal("0.90")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void vehicleHaircutUsesHalfUp() {
        CollateralValuationCalculator.EligibleValue value = CollateralValuationCalculator.value(
                CollateralType.MACHINERY,
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                null,
                null);
        assertThat(value.acceptedEligibleValue()).isEqualByComparingTo("45.00");
        assertThat(CollateralValuationCalculator.coverage(new BigDecimal("2"), new BigDecimal("3")))
                .isEqualByComparingTo("0.666667");
        assertThat(CollateralValuationCalculator.available(new BigDecimal("700000.00"), new BigDecimal("700000.00")))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void approvalRequiresVerificationAndACurrentValuation() {
        assertThatThrownBy(() -> CollateralRules.assertApprovable(approval(CheckStatus.PENDING)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("ownership");
        CollateralRules.assertApprovable(approval(CheckStatus.VERIFIED));
    }

    @Test
    void allocationCannotExceedEligibleValueOrAnExclusivePledge() {
        assertThatThrownBy(() -> CollateralRules.assertCanAllocate(allocation(new BigDecimal("400000.00"), 0, 1, true, new BigDecimal("300000.01"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("over-allocated");
        assertThatThrownBy(() -> CollateralRules.assertCanAllocate(allocation(new BigDecimal("100.00"), 1, 0, true, new BigDecimal("1.00"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already pledged");
        CollateralRules.assertCanAllocate(allocation(new BigDecimal("400000.00"), 0, 1, true, new BigDecimal("300000.00")));
    }

    @Test
    void releaseNeedsAnotherOfficerAndADischargedFacility() {
        assertThatThrownBy(() -> CollateralRules.assertCanRelease(new CollateralRules.ReleaseCheck(
                VerificationStatus.APPROVED, java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                java.util.List.of(ApplicationStatus.SUBMITTED))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("settled");
        java.util.UUID approver = java.util.UUID.randomUUID();
        assertThatThrownBy(() -> CollateralRules.assertCanRelease(new CollateralRules.ReleaseCheck(
                VerificationStatus.APPROVED, approver, approver, java.util.List.of(ApplicationStatus.DISBURSED))))
                .isInstanceOf(ForbiddenException.class);
        CollateralRules.assertCanRelease(new CollateralRules.ReleaseCheck(
                VerificationStatus.APPROVED, approver, java.util.UUID.randomUUID(), java.util.List.of(ApplicationStatus.DISBURSED)));
    }

    private static CollateralRules.ApprovalCheck approval(CheckStatus ownership) {
        return new CollateralRules.ApprovalCheck(
                CollateralType.RESIDENTIAL_PROPERTY,
                ownership,
                CheckStatus.VERIFIED,
                CheckStatus.VERIFIED,
                InsuranceCheckStatus.VERIFIED,
                EncumbranceStatus.CLEAR,
                true,
                true,
                true,
                new BigDecimal("1000000.00"),
                TODAY.plusMonths(6),
                false,
                ValuationStatus.APPROVED,
                TODAY.plusMonths(6),
                new BigDecimal("700000.00"),
                TODAY);
    }

    private static CollateralRules.AllocationCheck allocation(
            BigDecimal already,
            int exclusive,
            int shared,
            boolean requestedShared,
            BigDecimal requested) {
        return new CollateralRules.AllocationCheck(
                VerificationStatus.APPROVED,
                TODAY.plusMonths(6),
                new BigDecimal("700000.00"),
                already,
                exclusive,
                shared,
                requestedShared,
                requested,
                ApplicationStatus.SUBMITTED,
                TODAY);
    }
}
