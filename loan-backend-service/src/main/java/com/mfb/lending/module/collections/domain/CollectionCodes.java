package com.mfb.lending.module.collections.domain;

public final class CollectionCodes {

    private CollectionCodes() {
    }

    public enum RestructuringType {
        TENOR_EXTENSION,
        FREQUENCY_CHANGE,
        REVISED_AMOUNT,
        MORATORIUM,
        INTEREST_RESTRUCTURE,
        PARTIAL_SETTLEMENT,
        MATURITY_REVISION
    }

    public enum ClosureOutcome {
        RECOVERED,
        PARTIAL_RECOVERY,
        RESTRUCTURED,
        UNCOLLECTIBLE
    }

    public enum ActivityType {
        CONTACT,
        CALL,
        VISIT,
        NOTE,
        PAYMENT_FOLLOW_UP,
        PROMISE,
        ESCALATION,
        CLOSURE
    }

    public enum ContactChannel {
        PHONE,
        SMS,
        EMAIL,
        VISIT,
        BRANCH,
        LETTER
    }
}
