package com.mfb.lending.module.customer.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record SmeProfile(
        UUID customerId,
        String registeredName,
        String tradingName,
        String cacNumber,
        BusinessType businessType,
        String businessSector,
        LocalDate incorporationDate,
        BigDecimal annualTurnover,
        BigDecimal monthlyRevenue,
        BigDecimal operatingExpenses,
        StoredIdentity tin
) {
}
