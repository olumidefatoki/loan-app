package com.mfb.lending.module.loanapplication.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LoanApplicationResponse(
        UUID id,
        String reference,
        UUID customerId,
        String customerSegment,
        UUID productId,
        String productCode,
        String productName,
        UUID productVersionId,
        int productVersionNumber,
        String status,
        int revisionNumber,
        boolean correctionOpen,
        BigDecimal amount,
        String currencyCode,
        String purpose,
        Integer tenure,
        String tenureUnit,
        String repaymentFrequency,
        UUID disbursementAccountId,
        String disbursementAccountMasked,
        IndividualFinancials individualFinancials,
        SmeFinancials smeFinancials,
        List<ObligationItem> obligations,
        List<GuarantorItem> guarantors,
        List<CollateralItem> collaterals,
        List<DocumentRequirement> documents,
        String channel,
        Instant submittedAt,
        Instant withdrawnAt,
        String decisionReason,
        Instant createdAt,
        Instant updatedAt
) {

    public record IndividualFinancials(
            BigDecimal monthlyIncome,
            BigDecimal otherIncome,
            BigDecimal monthlyExpenses,
            String employmentStatus,
            String employerName,
            String jobTitle,
            LocalDate employmentStartDate
    ) {
    }

    public record SmeFinancials(
            BigDecimal annualTurnover,
            BigDecimal monthlyTurnover,
            BigDecimal operatingExpenses,
            BigDecimal monthlyCashFlow,
            BigDecimal businessLiabilities,
            Boolean authorisedToBorrow,
            String authorisedBorrowerName,
            BigDecimal authorisedBorrowingLimit
    ) {
    }

    public record ObligationItem(String lender, BigDecimal outstandingBalance, BigDecimal monthlyRepayment) {
    }

    public record GuarantorItem(
            UUID id,
            String fullName,
            String phoneNumber,
            String email,
            String relationship,
            String occupation,
            String addressLine
    ) {
    }

    public record CollateralItem(
            UUID id,
            String collateralType,
            String description,
            BigDecimal estimatedValue,
            String ownerName,
            String location
    ) {
    }

    public record DocumentRequirement(
            String documentCode,
            String documentName,
            boolean required,
            boolean uploaded,
            UUID documentId
    ) {
    }
}
