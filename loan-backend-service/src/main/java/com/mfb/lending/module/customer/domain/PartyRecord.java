package com.mfb.lending.module.customer.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PartyRecord(
        UUID id,
        UUID customerId,
        String fullName,
        LocalDate dateOfBirth,
        String phoneNumber,
        String email,
        String position,
        BigDecimal ownershipPercentage,
        StoredIdentity bvn,
        StoredIdentity nin
) {
}
