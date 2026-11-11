package com.mfb.lending.module.credit.bureau;

import java.math.BigDecimal;

import com.mfb.lending.module.credit.domain.BureauCheckStatus;
import com.mfb.lending.module.credit.domain.RepaymentHistory;

public record MappedBureauResponse(
        String providerCode,
        BureauCheckStatus status,
        Boolean identificationMatched,
        Integer score,
        BigDecimal existingExposure,
        RepaymentHistory repaymentHistory,
        String failureReason
) {
}
