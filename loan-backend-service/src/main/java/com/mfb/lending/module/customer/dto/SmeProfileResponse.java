package com.mfb.lending.module.customer.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SmeProfileResponse(
        String registeredBusinessName,
        String tradingName,
        String cacRegistrationNumber,
        String businessType,
        String businessSector,
        LocalDate incorporationDate,
        BigDecimal annualTurnover,
        BigDecimal monthlyRevenue,
        BigDecimal operatingExpenses,
        String taxIdentificationNumberMasked
) {
}
