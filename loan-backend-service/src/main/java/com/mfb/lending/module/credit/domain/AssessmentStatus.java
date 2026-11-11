package com.mfb.lending.module.credit.domain;

public enum AssessmentStatus {
    OPEN,
    EVALUATED,
    REFERRED,
    PENDING_INFORMATION,
    COMPLIANCE_HOLD,
    CLOSED;

    public static AssessmentStatus forDecision(DecisionOutcome decision) {
        return switch (decision) {
            case AUTO_APPROVE, AUTO_REJECT -> EVALUATED;
            case REFER -> REFERRED;
            case PENDING_DATA -> PENDING_INFORMATION;
            case COMPLIANCE_HOLD -> COMPLIANCE_HOLD;
        };
    }
}
