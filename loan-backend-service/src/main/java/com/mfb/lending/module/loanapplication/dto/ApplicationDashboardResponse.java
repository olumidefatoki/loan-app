package com.mfb.lending.module.loanapplication.dto;

import java.math.BigDecimal;

public record ApplicationDashboardResponse(
        long total,
        long drafts,
        long inProgress,
        long offers,
        long approved,
        long disbursed,
        long closed,
        BigDecimal requestedAmount
) {
}
