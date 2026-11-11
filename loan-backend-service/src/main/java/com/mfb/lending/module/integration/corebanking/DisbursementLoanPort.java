package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.util.UUID;

import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;

public interface DisbursementLoanPort {

    DisbursableLoan load(UUID applicationId, UUID accountId, BigDecimal amount);

    void markDisbursed(UUID applicationId, String reference, UUID actorId, String actorName);

    record DisbursableLoan(
            UUID applicationId,
            String applicationReference,
            UUID customerId,
            UUID accountId,
            BigDecimal amount,
            String currencyCode,
            ApplicationStatus status
    ) {
    }
}
