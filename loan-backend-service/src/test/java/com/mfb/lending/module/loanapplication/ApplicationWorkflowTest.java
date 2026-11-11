package com.mfb.lending.module.loanapplication;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.loanapplication.application.ApplicationWorkflow;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApplicationWorkflowTest {

    @Test
    void originationCanMoveThroughTheDefinedPath() {
        assertTransition(ApplicationStatus.DRAFT, ApplicationStatus.SUBMITTED);
        assertTransition(ApplicationStatus.SUBMITTED, ApplicationStatus.KYC_PENDING);
        assertTransition(ApplicationStatus.KYC_PENDING, ApplicationStatus.UNDER_REVIEW);
        assertTransition(ApplicationStatus.UNDER_REVIEW, ApplicationStatus.CREDIT_ASSESSMENT);
        assertTransition(ApplicationStatus.CREDIT_ASSESSMENT, ApplicationStatus.PENDING_APPROVAL);
        assertTransition(ApplicationStatus.PENDING_APPROVAL, ApplicationStatus.APPROVED);
        assertTransition(ApplicationStatus.APPROVED, ApplicationStatus.OFFER_ISSUED);
        assertTransition(ApplicationStatus.OFFER_ISSUED, ApplicationStatus.OFFER_ACCEPTED);
        assertTransition(ApplicationStatus.OFFER_ACCEPTED, ApplicationStatus.PENDING_DISBURSEMENT);
        assertTransition(ApplicationStatus.PENDING_DISBURSEMENT, ApplicationStatus.DISBURSED);
    }

    @Test
    void correctionReferralHoldAndTerminalBranchesAreAllowed() {
        assertTransition(ApplicationStatus.SUBMITTED, ApplicationStatus.DOCUMENTS_PENDING);
        assertTransition(ApplicationStatus.DOCUMENTS_PENDING, ApplicationStatus.SUBMITTED);
        assertTransition(ApplicationStatus.UNDER_REVIEW, ApplicationStatus.REFERRED);
        assertTransition(ApplicationStatus.REFERRED, ApplicationStatus.UNDER_REVIEW);
        assertTransition(ApplicationStatus.UNDER_REVIEW, ApplicationStatus.COMPLIANCE_HOLD);
        assertTransition(ApplicationStatus.COMPLIANCE_HOLD, ApplicationStatus.KYC_PENDING);
        assertTransition(ApplicationStatus.CREDIT_ASSESSMENT, ApplicationStatus.REJECTED);
        assertTransition(ApplicationStatus.PENDING_APPROVAL, ApplicationStatus.DOCUMENTS_PENDING);
        assertTransition(ApplicationStatus.APPROVED, ApplicationStatus.PENDING_APPROVAL);
        assertTransition(ApplicationStatus.APPROVED, ApplicationStatus.EXPIRED);
        assertTransition(ApplicationStatus.OFFER_ISSUED, ApplicationStatus.WITHDRAWN);
        assertTransition(ApplicationStatus.PENDING_DISBURSEMENT, ApplicationStatus.CANCELLED);
        assertTransition(ApplicationStatus.DRAFT, ApplicationStatus.WITHDRAWN);
    }

    @Test
    void skippedAndTerminalTransitionsAreRejected() {
        assertRejected(ApplicationStatus.DRAFT, ApplicationStatus.APPROVED);
        assertRejected(ApplicationStatus.SUBMITTED, ApplicationStatus.DISBURSED);
        assertRejected(ApplicationStatus.OFFER_ISSUED, ApplicationStatus.DISBURSED);
        assertRejected(ApplicationStatus.DISBURSED, ApplicationStatus.SUBMITTED);
        assertRejected(ApplicationStatus.REJECTED, ApplicationStatus.DRAFT);
        assertRejected(ApplicationStatus.WITHDRAWN, ApplicationStatus.SUBMITTED);
        assertRejected(ApplicationStatus.CANCELLED, ApplicationStatus.UNDER_REVIEW);
        assertRejected(ApplicationStatus.EXPIRED, ApplicationStatus.APPROVED);
    }

    @Test
    void draftsAndReturnedApplicationsAreTheOnlyEditableStates() {
        assertThat(ApplicationWorkflow.editable(ApplicationStatus.DRAFT, false)).isTrue();
        assertThat(ApplicationWorkflow.editable(ApplicationStatus.DOCUMENTS_PENDING, true)).isTrue();
        assertThat(ApplicationWorkflow.editable(ApplicationStatus.DOCUMENTS_PENDING, false)).isFalse();
        assertThat(ApplicationWorkflow.editable(ApplicationStatus.SUBMITTED, false)).isFalse();
        assertThat(ApplicationWorkflow.terminal(ApplicationStatus.DISBURSED)).isTrue();
        assertThat(ApplicationWorkflow.terminal(ApplicationStatus.REJECTED)).isTrue();
        assertThat(ApplicationWorkflow.countsAsActive(ApplicationStatus.SUBMITTED)).isTrue();
        assertThat(ApplicationWorkflow.countsAsActive(ApplicationStatus.DISBURSED)).isFalse();
        assertThat(ApplicationWorkflow.customerControlled(ApplicationStatus.WITHDRAWN)).isTrue();
        assertThat(ApplicationWorkflow.customerControlled(ApplicationStatus.UNDER_REVIEW)).isFalse();
        assertThat(ApplicationWorkflow.reasonRequired(ApplicationStatus.DOCUMENTS_PENDING)).isTrue();
        assertThat(ApplicationWorkflow.reasonRequired(ApplicationStatus.APPROVED)).isFalse();
    }

    private static void assertTransition(ApplicationStatus from, ApplicationStatus to) {
        ApplicationWorkflow.assertTransition(from, to);
    }

    private static void assertRejected(ApplicationStatus from, ApplicationStatus to) {
        assertThatThrownBy(() -> ApplicationWorkflow.assertTransition(from, to))
                .isInstanceOf(ValidationException.class)
                .satisfies(thrown -> assertThat(((ValidationException) thrown).getViolations())
                        .anySatisfy(violation -> assertThat(violation.message()).contains(from.name()).contains(to.name())));
    }
}
