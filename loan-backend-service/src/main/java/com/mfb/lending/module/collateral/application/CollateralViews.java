package com.mfb.lending.module.collateral.application;

import java.time.LocalDate;
import java.util.List;

import com.mfb.lending.module.collateral.domain.AllocationRecord;
import com.mfb.lending.module.collateral.domain.CollateralDocumentRecord;
import com.mfb.lending.module.collateral.domain.CollateralRecord;
import com.mfb.lending.module.collateral.domain.EncumbranceRecord;
import com.mfb.lending.module.collateral.domain.GuarantorDocumentRecord;
import com.mfb.lending.module.collateral.domain.GuarantorRecord;
import com.mfb.lending.module.collateral.domain.InsuranceRecord;
import com.mfb.lending.module.collateral.domain.LoanGuarantorRecord;
import com.mfb.lending.module.collateral.domain.ReleaseRecord;
import com.mfb.lending.module.collateral.domain.ValuationRecord;
import com.mfb.lending.module.collateral.domain.VerificationStatus;
import com.mfb.lending.module.collateral.dto.AllocationSummaryResponse;
import com.mfb.lending.module.collateral.dto.CollateralDetailResponse;
import com.mfb.lending.module.collateral.dto.CollateralResponse;
import com.mfb.lending.module.collateral.dto.EncumbranceResponse;
import com.mfb.lending.module.collateral.dto.GuarantorResponse;
import com.mfb.lending.module.collateral.dto.InsuranceResponse;
import com.mfb.lending.module.collateral.dto.LoanCollateralResponse;
import com.mfb.lending.module.collateral.dto.LoanGuarantorResponse;
import com.mfb.lending.module.collateral.dto.ReleaseResponse;
import com.mfb.lending.module.collateral.dto.StoredDocumentResponse;
import com.mfb.lending.module.collateral.dto.ValuationResponse;
import com.mfb.lending.module.customer.application.IdentityNumbers;

final class CollateralViews {

    private CollateralViews() {
    }

    static CollateralResponse collateral(CollateralRecord record, java.math.BigDecimal allocated, LocalDate today) {
        boolean expired = record.valuationExpiresOn() != null && record.valuationExpiresOn().isBefore(today);
        boolean pledgeable = record.verificationStatus() == VerificationStatus.APPROVED && !expired;
        return new CollateralResponse(
                record.id(),
                record.reference(),
                record.customerId(),
                record.type().name(),
                record.description(),
                record.ownerName(),
                record.ownerIdentifier(),
                record.identificationNumber(),
                record.location(),
                record.marketValue(),
                record.forcedSaleValue(),
                record.eligibleLendingValue(),
                "NGN",
                record.lendingRatio(),
                record.valuationDate(),
                record.valuationExpiresOn(),
                expired,
                record.valuerName(),
                record.valuerReference(),
                record.verificationStatus().name(),
                record.ownershipStatus().name(),
                record.documentStatus().name(),
                record.legalStatus().name(),
                record.insuranceStatus().name(),
                record.encumbranceStatus().name(),
                allocated,
                pledgeable
                        ? CollateralValuationCalculator.available(record.eligibleLendingValue(), allocated)
                        : new java.math.BigDecimal("0.00"),
                record.approvedBy(),
                record.createdAt(),
                record.updatedAt());
    }

    static CollateralDetailResponse detail(
            CollateralRecord record,
            java.math.BigDecimal allocated,
            List<ValuationRecord> valuations,
            List<CollateralDocumentRecord> documents,
            InsuranceRecord insurance,
            List<EncumbranceRecord> encumbrances,
            List<AllocationRecord> allocations,
            List<ReleaseRecord> releases,
            LocalDate today) {
        return new CollateralDetailResponse(
                collateral(record, allocated, today),
                valuations.stream().map(CollateralViews::valuation).toList(),
                documents.stream().map(CollateralViews::document).toList(),
                insurance == null ? null : insurance(insurance),
                encumbrances.stream().map(CollateralViews::encumbrance).toList(),
                allocations.stream().map(CollateralViews::allocation).toList(),
                releases.stream().map(CollateralViews::release).toList());
    }

    static ValuationResponse valuation(ValuationRecord record) {
        return new ValuationResponse(
                record.id(),
                record.kind().name(),
                record.status().name(),
                record.marketValue(),
                record.forcedSaleValue(),
                record.lendingRatio(),
                record.calculatedEligibleValue(),
                record.acceptedEligibleValue(),
                record.valuationDate(),
                record.expiryDate(),
                record.valuerName(),
                record.valuerReference(),
                record.submittedBy(),
                record.decidedBy(),
                record.decidedAt(),
                record.decisionReason(),
                record.createdAt());
    }

    static StoredDocumentResponse document(CollateralDocumentRecord record) {
        return new StoredDocumentResponse(
                record.id(),
                record.code().name(),
                record.fileName(),
                record.contentType(),
                record.sizeBytes(),
                record.checksumSha256(),
                record.createdAt());
    }

    static StoredDocumentResponse document(GuarantorDocumentRecord record) {
        return new StoredDocumentResponse(
                record.id(),
                record.code().name(),
                record.fileName(),
                record.contentType(),
                record.sizeBytes(),
                record.checksumSha256(),
                record.createdAt());
    }

    static InsuranceResponse insurance(InsuranceRecord record) {
        return new InsuranceResponse(
                record.id(),
                record.insurerName(),
                record.policyNumber(),
                record.coverAmount(),
                "NGN",
                record.startDate(),
                record.expiryDate());
    }

    static EncumbranceResponse encumbrance(EncumbranceRecord record) {
        return new EncumbranceResponse(
                record.id(),
                record.loanCollateralId(),
                record.source().name(),
                record.holderName(),
                record.referenceNumber(),
                record.amount(),
                record.status().name(),
                record.notedAt(),
                record.releasedAt());
    }

    static AllocationSummaryResponse allocation(AllocationRecord record) {
        return new AllocationSummaryResponse(
                record.id(),
                record.applicationId(),
                record.allocatedAmount(),
                record.sharedSecurity(),
                record.status().name(),
                record.allocatedAt(),
                record.releasedAt());
    }

    static ReleaseResponse release(ReleaseRecord record) {
        return new ReleaseResponse(
                record.id(),
                record.settlementReference(),
                record.dischargeReference(),
                record.reason(),
                record.releasedBy(),
                record.releasedAt());
    }

    static LoanCollateralResponse loanCollateral(
            AllocationRecord allocation,
            CollateralRecord collateral,
            java.math.BigDecimal available,
            java.math.BigDecimal coverage) {
        return new LoanCollateralResponse(
                allocation.id(),
                allocation.applicationId(),
                collateral.id(),
                collateral.reference(),
                collateral.type().name(),
                allocation.allocatedAmount(),
                allocation.sharedSecurity(),
                allocation.status().name(),
                collateral.eligibleLendingValue(),
                available,
                coverage,
                collateral.verificationStatus().name());
    }

    static GuarantorResponse guarantor(GuarantorRecord record, List<GuarantorDocumentRecord> documents) {
        return new GuarantorResponse(
                record.id(),
                record.reference(),
                record.fullName(),
                record.phoneNumber(),
                record.email(),
                record.addressLine(),
                IdentityNumbers.maskedLast4(record.bvnLast4()),
                IdentityNumbers.maskedLast4(record.ninLast4()),
                record.bvnVerificationReference(),
                record.ninVerificationReference(),
                record.employmentStatus().name(),
                record.employerName(),
                record.jobTitle(),
                record.monthlyIncome(),
                record.monthlyObligations(),
                record.verificationStatus().name(),
                record.acceptanceStatus().name(),
                record.approvedSecurity(),
                record.consentRecorded(),
                record.rejectionReason(),
                documents.stream().map(CollateralViews::document).toList(),
                record.createdAt(),
                record.updatedAt());
    }

    static LoanGuarantorResponse loanGuarantor(LoanGuarantorRecord link, GuarantorRecord guarantor) {
        return new LoanGuarantorResponse(
                link.id(),
                link.applicationId(),
                guarantor.id(),
                guarantor.reference(),
                guarantor.fullName(),
                link.relationship(),
                link.approvedSecurity(),
                guarantor.verificationStatus().name(),
                guarantor.acceptanceStatus().name(),
                guarantor.consentRecorded(),
                link.linkedAt());
    }
}
