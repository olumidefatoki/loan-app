package com.mfb.lending.module.credit.domain;

import java.math.BigDecimal;

public record BureauView(
        BureauCheckStatus status,
        Integer score,
        BigDecimal existingExposure,
        RepaymentHistory reportedRepayment
) {

    public static BureauView none() {
        return new BureauView(null, null, null, null);
    }

    public boolean completed() {
        return status == BureauCheckStatus.COMPLETED;
    }
}
