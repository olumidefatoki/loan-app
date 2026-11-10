package com.mfb.lending.module.loanapplication.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.mfb.lending.module.customer.domain.ClientChannel;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;

public record ApplicationSummary(
        UUID id,
        String reference,
        UUID customerId,
        String customerName,
        CustomerKind segment,
        UUID productId,
        String productCode,
        String productName,
        ApplicationStatus status,
        BigDecimal amount,
        String currencyCode,
        Integer tenure,
        TenureUnit tenureUnit,
        RepaymentFrequency repaymentFrequency,
        int revisionNumber,
        boolean correctionOpen,
        ClientChannel channel,
        Instant submittedAt,
        Instant updatedAt
) {
}
