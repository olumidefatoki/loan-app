package com.mfb.lending.module.customer.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record IndividualProfile(
        UUID customerId,
        String fullName,
        LocalDate dateOfBirth,
        String phoneNumber,
        String email,
        StoredIdentity bvn,
        StoredIdentity nin,
        EmploymentStatus employmentStatus,
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
