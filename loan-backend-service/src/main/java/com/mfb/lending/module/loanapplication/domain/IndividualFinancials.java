package com.mfb.lending.module.loanapplication.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

public record IndividualFinancials(
        BigDecimal monthlyIncome,
        BigDecimal otherIncome,
        BigDecimal monthlyExpenses,
        EmploymentStatus employmentStatus,
        String employerName,
        String jobTitle,
        LocalDate employmentStartDate
) {

    public boolean captured() {
        return monthlyIncome != null
                || otherIncome != null
                || monthlyExpenses != null
                || employmentStatus != null
                || employerName != null
                || jobTitle != null
                || employmentStartDate != null;
    }
}
