package com.mfb.lending.module.loanproduct.domain;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductFee(
        UUID id,
        UUID versionId,
        FeeType feeType,
        String name,
        FeeCalculation calculation,
        BigDecimal amount,
        BigDecimal rate
) {
}
