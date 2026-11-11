package com.mfb.lending.module.servicing.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record RepaymentAllocation(
        UUID id,
        UUID repaymentId,
        String component,
        BigDecimal amount,
        UUID scheduleLineId,
        String currencyCode
) {
}
