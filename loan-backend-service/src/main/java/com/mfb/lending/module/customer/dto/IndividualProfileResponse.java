package com.mfb.lending.module.customer.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record IndividualProfileResponse(
        String fullName,
        LocalDate dateOfBirth,
        String phoneNumber,
        String email,
        String bvnMasked,
        String ninMasked,
        String employmentStatus,
        String employerName,
        String occupation,
        BigDecimal monthlyIncome,
        BigDecimal monthlyExpenses,
        String nextOfKinName,
        String nextOfKinRelationship,
        String nextOfKinPhone,
        String nextOfKinAddress
) {
}
