package com.mfb.lending.module.loanapplication.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record CollateralRecord(
        UUID id,
        UUID applicationId,
        String collateralType,
        String description,
        BigDecimal estimatedValue,
        String ownerName,
        String location
) {
}
