package com.mfb.lending.module.loanoffer.domain;

public enum OfferStatus {
    DRAFT,
    ISSUED,
    ACCEPTED,
    DECLINED,
    EXPIRED,
    CANCELLED;

    public boolean open() {
        return this == DRAFT || this == ISSUED;
    }
}
