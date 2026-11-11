package com.mfb.lending.module.collateral.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.collateral.domain.CheckStatus;
import com.mfb.lending.module.collateral.domain.CollateralType;
import com.mfb.lending.module.collateral.domain.EncumbranceStatus;
import com.mfb.lending.module.collateral.domain.InsuranceCheckStatus;
import com.mfb.lending.module.collateral.domain.VerificationStatus;
import com.mfb.lending.module.collateral.domain.ValuationStatus;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;

public final class CollateralRules {

    private static final Set<ApplicationStatus> PLEDGEABLE = EnumSet.of(
            ApplicationStatus.DRAFT,
            ApplicationStatus.SUBMITTED,
            ApplicationStatus.KYC_PENDING,
            ApplicationStatus.DOCUMENTS_PENDING,
            ApplicationStatus.UNDER_REVIEW,
            ApplicationStatus.CREDIT_ASSESSMENT,
            ApplicationStatus.REFERRED,
            ApplicationStatus.COMPLIANCE_HOLD,
            ApplicationStatus.PENDING_APPROVAL,
            ApplicationStatus.APPROVED,
            ApplicationStatus.OFFER_ISSUED,
            ApplicationStatus.OFFER_ACCEPTED,
            ApplicationStatus.PENDING_DISBURSEMENT);

    private static final Set<ApplicationStatus> DISCHARGED = EnumSet.of(
            ApplicationStatus.REJECTED,
            ApplicationStatus.WITHDRAWN,
            ApplicationStatus.CANCELLED,
            ApplicationStatus.EXPIRED,
            ApplicationStatus.DISBURSED);

    private CollateralRules() {
    }

    public static LocalDate expiry(LocalDate valuationDate, LocalDate requestedExpiry, LocalDate today) {
        if (valuationDate == null) {
            throw ValidationException.field("valuationDate", "Valuation date is required");
        }
        if (valuationDate.isAfter(today)) {
            throw ValidationException.field("valuationDate", "Valuation date cannot be in the future");
        }
        LocalDate expiry = requestedExpiry == null ? valuationDate.plusMonths(12) : requestedExpiry;
        if (expiry.isBefore(valuationDate)) {
            throw ValidationException.field("expiryDate", "Valuation expiry cannot be before the valuation date");
        }
        if (expiry.isBefore(today)) {
            throw ValidationException.field("expiryDate", "Collateral valuation has already expired");
        }
        return expiry;
    }

    public static void assertApprovable(ApprovalCheck check) {
        if (check.ownershipStatus() != CheckStatus.VERIFIED) {
            throw new ConflictException("Collateral ownership must be verified");
        }
        if (check.documentStatus() != CheckStatus.VERIFIED || !check.anyDocument()) {
            throw new ConflictException("Collateral documents must be verified");
        }
        if (check.legalStatus() != CheckStatus.VERIFIED || !check.legalDocument()) {
            throw new ConflictException("Legal verification is incomplete");
        }
        if (check.insuranceStatus() == InsuranceCheckStatus.NOT_REQUIRED) {
            if (!check.type().insuranceOptional()) {
                throw new ConflictException("Insurance must be verified");
            }
        } else if (check.insuranceStatus() != InsuranceCheckStatus.VERIFIED
                || !check.insuranceDocument()
                || check.insuranceCover() == null
                || check.insuranceCover().signum() <= 0
                || check.insuranceExpiry() == null
                || check.insuranceExpiry().isBefore(check.today())) {
            throw new ConflictException("Insurance must be verified");
        }
        if (check.encumbranceStatus() != EncumbranceStatus.CLEAR || check.externalEncumbrance()) {
            throw new ConflictException("Collateral has an existing encumbrance");
        }
        if (check.valuationStatus() != ValuationStatus.APPROVED) {
            throw new ConflictException("An approved valuation is required");
        }
        if (check.valuationExpiry() == null || check.valuationExpiry().isBefore(check.today())) {
            throw new ConflictException("Collateral valuation has expired");
        }
        if (check.eligibleValue() == null || check.eligibleValue().signum() <= 0) {
            throw new ConflictException("Collateral has no eligible lending value");
        }
    }

    public static void assertCanAllocate(AllocationCheck check) {
        if (check.verificationStatus() != VerificationStatus.APPROVED) {
            throw new ConflictException("Collateral is not approved security");
        }
        if (check.valuationExpiry() == null || check.valuationExpiry().isBefore(check.today())) {
            throw new ConflictException("Collateral valuation has expired");
        }
        if (check.eligibleValue() == null || check.eligibleValue().signum() <= 0) {
            throw new ConflictException("Collateral has no eligible lending value");
        }
        if (!PLEDGEABLE.contains(check.applicationStatus())) {
            throw new ConflictException("Collateral cannot be pledged to this application");
        }
        if (check.requestedAmount() == null || check.requestedAmount().signum() <= 0) {
            throw ValidationException.field("allocatedAmount", "Allocated amount must be greater than zero");
        }
        boolean occupied = check.exclusivePledges() > 0 || check.sharedPledges() > 0;
        if (!check.sharedSecurity() && occupied) {
            throw new ConflictException("Collateral is already pledged");
        }
        if (check.sharedSecurity() && check.exclusivePledges() > 0) {
            throw new ConflictException("Collateral is already pledged");
        }
        BigDecimal next = check.allocatedAmount().add(check.requestedAmount());
        if (next.compareTo(check.eligibleValue()) > 0) {
            throw new ConflictException("Collateral cannot be over-allocated");
        }
    }

    public static void assertCanRelease(ReleaseCheck check) {
        if (check.verificationStatus() != VerificationStatus.APPROVED) {
            throw new ConflictException("Only approved collateral can be released");
        }
        if (check.approvedBy() == null || check.approvedBy().equals(check.releaserId())) {
            throw new ForbiddenException("Release requires a different authorised officer");
        }
        for (ApplicationStatus status : check.linkedStatuses()) {
            if (!DISCHARGED.contains(status)) {
                throw new ConflictException("Collateral is still pledged to an application that has not been settled or discharged");
            }
        }
    }

    public static boolean pledgeable(ApplicationStatus status) {
        return PLEDGEABLE.contains(status);
    }

    public record ApprovalCheck(
            CollateralType type,
            CheckStatus ownershipStatus,
            CheckStatus documentStatus,
            CheckStatus legalStatus,
            InsuranceCheckStatus insuranceStatus,
            EncumbranceStatus encumbranceStatus,
            boolean anyDocument,
            boolean legalDocument,
            boolean insuranceDocument,
            BigDecimal insuranceCover,
            LocalDate insuranceExpiry,
            boolean externalEncumbrance,
            ValuationStatus valuationStatus,
            LocalDate valuationExpiry,
            BigDecimal eligibleValue,
            LocalDate today
    ) {
    }

    public record AllocationCheck(
            VerificationStatus verificationStatus,
            LocalDate valuationExpiry,
            BigDecimal eligibleValue,
            BigDecimal allocatedAmount,
            int exclusivePledges,
            int sharedPledges,
            boolean sharedSecurity,
            BigDecimal requestedAmount,
            ApplicationStatus applicationStatus,
            LocalDate today
    ) {
    }

    public record ReleaseCheck(
            VerificationStatus verificationStatus,
            UUID approvedBy,
            UUID releaserId,
            java.util.List<ApplicationStatus> linkedStatuses
    ) {
    }
}
