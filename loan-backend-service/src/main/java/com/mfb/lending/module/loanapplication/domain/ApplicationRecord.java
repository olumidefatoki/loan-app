package com.mfb.lending.module.loanapplication.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.mfb.lending.module.customer.domain.ClientChannel;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;

public record ApplicationRecord(
        UUID id,
        String reference,
        UUID customerId,
        UUID productId,
        UUID productVersionId,
        UUID branchId,
        CustomerKind segment,
        ApplicationStatus status,
        int revisionNumber,
        boolean correctionOpen,
        BigDecimal amount,
        String currencyCode,
        String purpose,
        Integer tenure,
        TenureUnit tenureUnit,
        RepaymentFrequency repaymentFrequency,
        UUID disbursementAccountId,
        ClientChannel channel,
        Instant submittedAt,
        Instant withdrawnAt,
        String decisionReason,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy
) {

    public ApplicationRecord withRevision(int revisionNumber, String updatedBy) {
        return new ApplicationRecord(
                id,
                reference,
                customerId,
                productId,
                productVersionId,
                branchId,
                segment,
                status,
                revisionNumber,
                correctionOpen,
                amount,
                currencyCode,
                purpose,
                tenure,
                tenureUnit,
                repaymentFrequency,
                disbursementAccountId,
                channel,
                submittedAt,
                withdrawnAt,
                decisionReason,
                createdAt,
                updatedAt,
                createdBy,
                updatedBy);
    }
}
