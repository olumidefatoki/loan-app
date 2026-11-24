package com.mfb.lending.module.collections.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.collections.domain.CollectionModels.InstalmentFact;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanPosition;
import com.mfb.lending.module.collections.domain.CollectionModels.PostedRepayment;
import com.mfb.lending.module.servicing.domain.LoanAccount;
import com.mfb.lending.module.servicing.domain.LoanRepayment;
import com.mfb.lending.module.servicing.domain.RepaymentAllocation;
import com.mfb.lending.module.servicing.domain.RepaymentStatus;
import com.mfb.lending.module.servicing.domain.ScheduleLine;
import com.mfb.lending.module.servicing.persistence.ServicingStore;
import org.springframework.stereotype.Component;

@Component
public class ServicingExposureSource {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final ServicingStore servicing;

    public ServicingExposureSource(ServicingStore servicing) {
        this.servicing = servicing;
    }

    public List<LoanPosition> portfolio() {
        List<LoanPosition> positions = new ArrayList<>();
        for (LoanAccount account : accounts("ACTIVE")) {
            LoanPosition position = position(account, false);
            if (position != null) {
                positions.add(position);
            }
        }
        for (LoanAccount account : accounts("OVERDUE")) {
            LoanPosition position = position(account, false);
            if (position != null) {
                positions.add(position);
            }
        }
        return positions;
    }

    public LoanPosition require(UUID loanAccountId) {
        LoanAccount account = servicing.findAccount(loanAccountId).orElseThrow(() -> new ResourceNotFoundException("Loan account"));
        LoanPosition position = position(account, true);
        if (position == null) {
            throw new ConflictException("The loan has no approved repayment schedule");
        }
        return position;
    }

    private List<LoanAccount> accounts(String status) {
        List<LoanAccount> accounts = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<LoanAccount> batch = servicing.listAccounts(null, status, 200, offset);
            accounts.addAll(batch);
            if (batch.size() < 200) {
                return accounts;
            }
            offset += batch.size();
        }
    }

    private LoanPosition position(LoanAccount account, boolean required) {
        if (account.currentScheduleId() == null) {
            if (required) {
                throw new ConflictException("The loan has no approved repayment schedule");
            }
            return null;
        }
        Map<UUID, BigDecimal> principal = new HashMap<>();
        Map<UUID, BigDecimal> interest = new HashMap<>();
        List<PostedRepayment> posted = new ArrayList<>();
        for (LoanRepayment repayment : servicing.repayments(account.id())) {
            if (repayment.status() != RepaymentStatus.POSTED) {
                continue;
            }
            posted.add(new PostedRepayment(repayment.id(), repayment.amount(), repayment.valueDate()));
            for (RepaymentAllocation allocation : servicing.allocations(repayment.id())) {
                if (allocation.scheduleLineId() == null) {
                    continue;
                }
                if ("PRINCIPAL".equals(allocation.component())) {
                    principal.merge(allocation.scheduleLineId(), allocation.amount(), BigDecimal::add);
                } else if ("INTEREST".equals(allocation.component())) {
                    interest.merge(allocation.scheduleLineId(), allocation.amount(), BigDecimal::add);
                }
            }
        }
        List<InstalmentFact> facts = new ArrayList<>();
        for (ScheduleLine line : servicing.lines(account.currentScheduleId())) {
            if ("WAIVED".equals(line.status())) {
                facts.add(new InstalmentFact(line.id(), line.number(), line.dueDate(), ZERO, ZERO, ZERO, ZERO));
                continue;
            }
            facts.add(new InstalmentFact(
                    line.id(), line.number(), line.dueDate(), line.principalDue(), line.interestDue(),
                    principal.getOrDefault(line.id(), ZERO), interest.getOrDefault(line.id(), ZERO)));
        }
        return new LoanPosition(
                account.id(), account.reference(), account.customerId(), account.branchId(), account.status().name(),
                account.currentScheduleId(), account.scheduleVersion(), account.repaymentFrequency(), account.interestRate(),
                account.repaymentDayOfMonth(), account.holidayRule(), account.outstandingPrincipal(), account.outstandingInterest(),
                account.outstandingFees(), account.outstandingPenalties(), facts, posted);
    }
}
