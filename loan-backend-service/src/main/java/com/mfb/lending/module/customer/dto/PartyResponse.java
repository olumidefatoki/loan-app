package com.mfb.lending.module.customer.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record PartyResponse(
        UUID id,
        String fullName,
        LocalDate dateOfBirth,
        String phoneNumber,
        String email,
        String position,
        BigDecimal ownershipPercentage,
        String bvnMasked,
        String ninMasked
) {
}
