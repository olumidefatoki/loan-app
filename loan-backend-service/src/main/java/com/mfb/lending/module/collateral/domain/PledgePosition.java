package com.mfb.lending.module.collateral.domain;

import java.math.BigDecimal;

public record PledgePosition(BigDecimal allocatedAmount, int exclusivePledges, int sharedPledges) {

    public static PledgePosition none() {
        return new PledgePosition(new BigDecimal("0.00"), 0, 0);
    }
}
