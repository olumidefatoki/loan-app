package com.mfb.lending.module.servicing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.mfb.lending.module.servicing.domain.RepaymentChannel;

public record RepaymentCommand(
        UUID loanAccountId,
        BigDecimal amount,
        RepaymentChannel channel,
        String idempotencyKey,
        LocalDate valueDate,
        String externalReference,
        boolean settlement,
        UUID actorId,
        String actorName
) {
}
