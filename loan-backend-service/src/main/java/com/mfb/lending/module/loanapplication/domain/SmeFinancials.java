package com.mfb.lending.module.loanapplication.domain;

import java.math.BigDecimal;

public record SmeFinancials(
        BigDecimal annualTurnover,
        BigDecimal monthlyTurnover,
        BigDecimal operatingExpenses,
        BigDecimal monthlyCashFlow,
        BigDecimal businessLiabilities,
        Boolean authorisedToBorrow,
        String authorisedBorrowerName,
        BigDecimal authorisedBorrowingLimit
) {

    public boolean captured() {
        return annualTurnover != null
                || monthlyTurnover != null
                || operatingExpenses != null
                || monthlyCashFlow != null
                || businessLiabilities != null
                || authorisedToBorrow != null
                || authorisedBorrowerName != null
                || authorisedBorrowingLimit != null;
    }
}
