package com.mfb.lending.module.loanapplication.application;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;

public final class ApplicationWorkflow {

    private static final Map<ApplicationStatus, Set<ApplicationStatus>> NEXT = new EnumMap<>(ApplicationStatus.class);

    static {
        for (ApplicationStatus status : ApplicationStatus.values()) {
            NEXT.put(status, Set.of());
        }
        allow(ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED, ApplicationStatus.WITHDRAWN);
        allow(ApplicationStatus.SUBMITTED,
                ApplicationStatus.KYC_PENDING,
                ApplicationStatus.DOCUMENTS_PENDING,
                ApplicationStatus.UNDER_REVIEW,
                ApplicationStatus.COMPLIANCE_HOLD,
                ApplicationStatus.REJECTED,
                ApplicationStatus.CANCELLED,
                ApplicationStatus.WITHDRAWN);
        allow(ApplicationStatus.KYC_PENDING,
                ApplicationStatus.DOCUMENTS_PENDING,
                ApplicationStatus.UNDER_REVIEW,
                ApplicationStatus.COMPLIANCE_HOLD,
                ApplicationStatus.REJECTED,
                ApplicationStatus.CANCELLED,
                ApplicationStatus.WITHDRAWN);
        allow(ApplicationStatus.DOCUMENTS_PENDING,
                ApplicationStatus.SUBMITTED,
                ApplicationStatus.KYC_PENDING,
                ApplicationStatus.UNDER_REVIEW,
                ApplicationStatus.COMPLIANCE_HOLD,
                ApplicationStatus.REJECTED,
                ApplicationStatus.CANCELLED,
                ApplicationStatus.WITHDRAWN);
        allow(ApplicationStatus.UNDER_REVIEW,
                ApplicationStatus.CREDIT_ASSESSMENT,
                ApplicationStatus.REFERRED,
                ApplicationStatus.DOCUMENTS_PENDING,
                ApplicationStatus.COMPLIANCE_HOLD,
                ApplicationStatus.REJECTED,
                ApplicationStatus.CANCELLED,
                ApplicationStatus.WITHDRAWN);
        allow(ApplicationStatus.CREDIT_ASSESSMENT,
                ApplicationStatus.PENDING_APPROVAL,
                ApplicationStatus.REFERRED,
                ApplicationStatus.UNDER_REVIEW,
                ApplicationStatus.COMPLIANCE_HOLD,
                ApplicationStatus.REJECTED,
                ApplicationStatus.CANCELLED,
                ApplicationStatus.WITHDRAWN);
        allow(ApplicationStatus.REFERRED,
                ApplicationStatus.UNDER_REVIEW,
                ApplicationStatus.CREDIT_ASSESSMENT,
                ApplicationStatus.COMPLIANCE_HOLD,
                ApplicationStatus.REJECTED,
                ApplicationStatus.CANCELLED,
                ApplicationStatus.WITHDRAWN);
        allow(ApplicationStatus.COMPLIANCE_HOLD,
                ApplicationStatus.UNDER_REVIEW,
                ApplicationStatus.KYC_PENDING,
                ApplicationStatus.DOCUMENTS_PENDING,
                ApplicationStatus.REJECTED,
                ApplicationStatus.CANCELLED,
                ApplicationStatus.WITHDRAWN);
        allow(ApplicationStatus.PENDING_APPROVAL,
                ApplicationStatus.APPROVED,
                ApplicationStatus.REJECTED,
                ApplicationStatus.REFERRED,
                ApplicationStatus.DOCUMENTS_PENDING,
                ApplicationStatus.COMPLIANCE_HOLD,
                ApplicationStatus.CANCELLED);
        allow(ApplicationStatus.APPROVED,
                ApplicationStatus.OFFER_ISSUED,
                ApplicationStatus.PENDING_APPROVAL,
                ApplicationStatus.CANCELLED,
                ApplicationStatus.EXPIRED);
        allow(ApplicationStatus.OFFER_ISSUED,
                ApplicationStatus.OFFER_ACCEPTED,
                ApplicationStatus.EXPIRED,
                ApplicationStatus.WITHDRAWN,
                ApplicationStatus.CANCELLED);
        allow(ApplicationStatus.OFFER_ACCEPTED,
                ApplicationStatus.PENDING_DISBURSEMENT,
                ApplicationStatus.CANCELLED);
        allow(ApplicationStatus.PENDING_DISBURSEMENT,
                ApplicationStatus.DISBURSED,
                ApplicationStatus.COMPLIANCE_HOLD,
                ApplicationStatus.CANCELLED);
    }

    private ApplicationWorkflow() {
    }

    public static void assertTransition(ApplicationStatus from, ApplicationStatus to) {
        if (!NEXT.get(from).contains(to)) {
            throw ValidationException.field("status", "Cannot move an application from " + from + " to " + to);
        }
    }

    public static boolean editable(ApplicationStatus status, boolean correctionOpen) {
        return status == ApplicationStatus.DRAFT
                || (status == ApplicationStatus.DOCUMENTS_PENDING && correctionOpen);
    }

    public static boolean terminal(ApplicationStatus status) {
        return NEXT.get(status).isEmpty();
    }

    public static boolean countsAsActive(ApplicationStatus status) {
        return !terminal(status);
    }

    public static boolean customerControlled(ApplicationStatus target) {
        return target == ApplicationStatus.SUBMITTED
                || target == ApplicationStatus.WITHDRAWN
                || target == ApplicationStatus.OFFER_ACCEPTED;
    }

    public static boolean reasonRequired(ApplicationStatus target) {
        return target == ApplicationStatus.REJECTED
                || target == ApplicationStatus.CANCELLED
                || target == ApplicationStatus.WITHDRAWN
                || target == ApplicationStatus.COMPLIANCE_HOLD
                || target == ApplicationStatus.REFERRED
                || target == ApplicationStatus.EXPIRED
                || target == ApplicationStatus.DOCUMENTS_PENDING;
    }

    public static Set<ApplicationStatus> allowed(ApplicationStatus from) {
        return NEXT.get(from);
    }

    private static void allow(ApplicationStatus from, ApplicationStatus... targets) {
        NEXT.put(from, Set.of(targets));
    }
}
