package com.mfb.lending.module.credit.bureau;

import com.mfb.lending.module.credit.domain.BureauCheckStatus;
import com.mfb.lending.module.credit.domain.RepaymentHistory;

public record BureauReport(
        BureauCheckStatus status,
        Integer score,
        RepaymentHistory repaymentHistory,
        String summary
) {
}
