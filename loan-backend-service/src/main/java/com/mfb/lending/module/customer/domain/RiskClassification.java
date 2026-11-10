package com.mfb.lending.module.customer.domain;

public enum RiskClassification {
    UNASSESSED,
    LOW,
    MEDIUM,
    HIGH,
    UNACCEPTABLE;

    public boolean atLeast(RiskClassification other) {
        return ordinal() >= other.ordinal();
    }

    public RiskClassification worse(RiskClassification other) {
        return ordinal() >= other.ordinal() ? this : other;
    }
}
