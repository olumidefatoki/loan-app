package com.mfb.lending.module.collateral.domain;

public enum GuarantorEmployment {
    EMPLOYED,
    SELF_EMPLOYED,
    UNEMPLOYED,
    RETIRED,
    STUDENT;

    public boolean requiresEmployer() {
        return this == EMPLOYED || this == SELF_EMPLOYED;
    }
}
