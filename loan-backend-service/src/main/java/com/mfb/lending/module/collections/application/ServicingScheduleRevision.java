package com.mfb.lending.module.collections.application;

import java.math.BigDecimal;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.module.servicing.application.ApprovedRestructure;
import com.mfb.lending.module.servicing.application.LoanServicing;
import com.mfb.lending.module.servicing.domain.LoanAccount;
import org.springframework.stereotype.Component;

@Component
public class ServicingScheduleRevision implements ScheduleRevisionPort {

    private final LoanServicing servicing;

    public ServicingScheduleRevision(LoanServicing servicing) {
        this.servicing = servicing;
    }

    @Override
    public Revision apply(RevisionRequest request) {
        LoanAccount before = servicing.get(request.loanAccountId());
        LoanAccount after = servicing.applyApprovedRestructure(approved(request));
        LoanServicing.ScheduleView current = servicing.schedule(after.id(), null);
        if (current.schedule().previousScheduleId() == null || !current.schedule().previousScheduleId().equals(before.currentScheduleId())) {
            throw new ConflictException("The original repayment schedule was not preserved");
        }
        LoanServicing.ScheduleView preserved = servicing.schedule(after.id(), before.scheduleVersion());
        if (!"SUPERSEDED".equals(preserved.schedule().status()) || preserved.lines().isEmpty()) {
            throw new ConflictException("The original repayment schedule was not preserved");
        }
        return new Revision(
                preserved.schedule().id(), preserved.schedule().versionNumber(), current.schedule().id(), current.schedule().versionNumber(),
                after.outstandingPrincipal(), after.outstandingInterest(), after.outstandingFees(), after.outstandingPenalties(), after.maturityDate());
    }

    @Override
    public BigDecimal quotedInstalment(RevisionRequest request) {
        return servicing.quotedInstalment(approved(request));
    }

    @Override
    public LoanServicing.Reconciliation reconcile(UUID loanAccountId) {
        return servicing.reconcile(loanAccountId);
    }

    private static ApprovedRestructure approved(RevisionRequest request) {
        return new ApprovedRestructure(
                request.loanAccountId(), request.tenure(), request.interestRate(), request.repaymentFrequency(),
                request.repaymentDayOfMonth(), request.holidayRule(), request.moratoriumDays(), request.expectedInstalment(),
                request.reason(), request.valueDate(), request.actorId(), request.actorName());
    }
}
