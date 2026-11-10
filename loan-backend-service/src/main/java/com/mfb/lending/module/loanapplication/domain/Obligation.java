package com.mfb.lending.module.loanapplication.domain;

import java.math.BigDecimal;

public record Obligation(
        String lender,
        BigDecimal outstandingBalance,
        BigDecimal monthlyRepayment
) {
}
