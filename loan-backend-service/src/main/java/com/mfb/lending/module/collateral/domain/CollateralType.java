package com.mfb.lending.module.collateral.domain;

import java.math.BigDecimal;

public enum CollateralType {
    LAND("0.50", true),
    RESIDENTIAL_PROPERTY("0.70", false),
    COMMERCIAL_PROPERTY("0.60", false),
    VEHICLE("0.50", false),
    EQUIPMENT("0.50", false),
    MACHINERY("0.45", false),
    INVENTORY("0.40", true),
    FIXED_DEPOSIT("0.90", true),
    RECEIVABLES("0.50", true),
    OTHER("0.40", true);

    private final BigDecimal policyRatio;
    private final boolean insuranceOptional;

    CollateralType(String policyRatio, boolean insuranceOptional) {
        this.policyRatio = new BigDecimal(policyRatio);
        this.insuranceOptional = insuranceOptional;
    }

    public BigDecimal policyRatio() {
        return policyRatio;
    }

    public boolean insuranceOptional() {
        return insuranceOptional;
    }
}
