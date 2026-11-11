package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.customer.application.CustomerEligibilityService;
import com.mfb.lending.module.customer.domain.AccountVerificationStatus;
import com.mfb.lending.module.customer.persistence.CustomerAccountRepository;
import com.mfb.lending.module.loanapplication.application.ApplicationWorkflow;
import com.mfb.lending.module.loanapplication.domain.ApplicationRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;
import com.mfb.lending.module.loanapplication.persistence.LoanApplicationRepository;
import org.springframework.stereotype.Component;

@Component
public class JdbcDisbursementLoanPort implements DisbursementLoanPort {

    private final LoanApplicationRepository applications;
    private final CustomerAccountRepository accounts;
    private final CustomerEligibilityService eligibility;
    private final ObjectMapper objectMapper;

    public JdbcDisbursementLoanPort(
            LoanApplicationRepository applications,
            CustomerAccountRepository accounts,
            CustomerEligibilityService eligibility,
            ObjectMapper objectMapper) {
        this.applications = applications;
        this.accounts = accounts;
        this.eligibility = eligibility;
        this.objectMapper = objectMapper;
    }

    @Override
    public DisbursableLoan load(UUID applicationId, UUID accountId, BigDecimal amount) {
        ApplicationRecord application = applications.lock(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        if (application.status() != ApplicationStatus.PENDING_DISBURSEMENT) {
            throw new ConflictException("The loan is not authorised for disbursement");
        }
        eligibility.assertDisbursementAllowed(application.customerId());
        if (application.amount() == null) {
            throw ValidationException.field("amount", "The approved loan has no amount");
        }
        if (amount != null && amount.compareTo(application.amount()) != 0) {
            throw ValidationException.field("amount", "amount must match the approved loan");
        }
        UUID destination = accountId == null ? application.disbursementAccountId() : accountId;
        if (destination == null) {
            throw ValidationException.field("accountId", "A destination account is required");
        }
        var account = accounts.find(application.customerId(), destination)
                .orElseThrow(() -> new ResourceNotFoundException("Destination account"));
        if (account.verificationStatus() != AccountVerificationStatus.VERIFIED) {
            throw new ConflictException("The destination account is not verified");
        }
        return new DisbursableLoan(
                application.id(),
                application.reference(),
                application.customerId(),
                destination,
                application.amount(),
                application.currencyCode(),
                application.status());
    }

    @Override
    public void markDisbursed(UUID applicationId, String reference, UUID actorId, String actorName) {
        if (actorId == null) {
            throw new IllegalStateException("Disbursement confirmation requires the authorising officer");
        }
        ApplicationRecord current = applications.lock(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        if (current.status() == ApplicationStatus.DISBURSED) {
            return;
        }
        ApplicationWorkflow.assertTransition(current.status(), ApplicationStatus.DISBURSED);
        String reason = "Core banking posting " + reference;
        applications.update(new ApplicationRecord(
                current.id(),
                current.reference(),
                current.customerId(),
                current.productId(),
                current.productVersionId(),
                current.branchId(),
                current.segment(),
                ApplicationStatus.DISBURSED,
                current.revisionNumber(),
                false,
                current.amount(),
                current.currencyCode(),
                current.purpose(),
                current.tenure(),
                current.tenureUnit(),
                current.repaymentFrequency(),
                current.disbursementAccountId(),
                current.channel(),
                current.submittedAt(),
                null,
                reason,
                current.createdAt(),
                current.updatedAt(),
                current.createdBy(),
                actorName));
        applications.insertHistory(UUID.randomUUID(), applicationId, current.status(), ApplicationStatus.DISBURSED, reason, actorId);
        applications.insertEvent(UUID.randomUUID(), applicationId, "DISBURSED", payload(reference), actorId);
    }

    private String payload(String reference) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("reference", reference));
        } catch (Exception ex) {
            throw new IllegalStateException("Disbursement event could not be written", ex);
        }
    }
}
