package com.mfb.lending.module.servicing.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.servicing.calculation.AccountingEngine.PostingRule;
import com.mfb.lending.module.servicing.calculation.StandardChart;
import com.mfb.lending.module.servicing.calculation.StandardChart.AllocationPolicy;
import com.mfb.lending.module.servicing.domain.AccountEvent;
import com.mfb.lending.module.servicing.domain.AccountingEvent;
import com.mfb.lending.module.servicing.domain.BalanceSnapshot;
import com.mfb.lending.module.servicing.domain.InterestAccrual;
import com.mfb.lending.module.servicing.domain.InterestAdjustment;
import com.mfb.lending.module.servicing.domain.JournalHeader;
import com.mfb.lending.module.servicing.domain.JournalLine;
import com.mfb.lending.module.servicing.domain.JournalStatus;
import com.mfb.lending.module.servicing.domain.LoanAccount;
import com.mfb.lending.module.servicing.domain.LoanCharge;
import com.mfb.lending.module.servicing.domain.LoanRepayment;
import com.mfb.lending.module.servicing.domain.RepaymentAllocation;
import com.mfb.lending.module.servicing.domain.RepaymentHistory;
import com.mfb.lending.module.servicing.domain.RepaymentStatus;
import com.mfb.lending.module.servicing.domain.ScheduleLine;
import com.mfb.lending.module.servicing.domain.ScheduleVersion;
import com.mfb.lending.module.servicing.domain.TransactionReversal;

public class InMemoryServicingStore implements ServicingStore {

    private final ConcurrentHashMap<UUID, Object> locks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, LoanAccount> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> byApplication = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ScheduleVersion> schedules = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<ScheduleLine>> lines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<BalanceSnapshot>> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<AccountEvent>> events = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<InterestAccrual>> accruals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, LoanRepayment> repayments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> repaymentKeys = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<RepaymentAllocation>> allocations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, JournalHeader> journals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, List<JournalLine>> journalLines = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, InterestAdjustment> adjustmentsByJournal = new ConcurrentHashMap<>();
    private final List<AllocationPolicy> policies = new ArrayList<>();
    private final AtomicLong accountSequence = new AtomicLong();
    private final AtomicLong repaymentSequence = new AtomicLong();
    private final AtomicLong journalSequence = new AtomicLong();
    private final Object keys = new Object();

    public InMemoryServicingStore() {
        policies.add(StandardChart.policy());
    }

    @Override
    public String nextAccountReference() {
        return "LA" + String.format("%012d", accountSequence.incrementAndGet());
    }

    @Override
    public String nextRepaymentReference() {
        return "RP" + String.format("%012d", repaymentSequence.incrementAndGet());
    }

    @Override
    public String nextJournalReference() {
        return "JE" + String.format("%012d", journalSequence.incrementAndGet());
    }

    @Override
    public void insertAccount(LoanAccount account) {
        synchronized (lock(account.id())) {
            if (byApplication.putIfAbsent(account.applicationId(), account.id()) != null) {
                throw new ConflictException("A loan account already exists for this application");
            }
            accounts.put(account.id(), account);
        }
    }

    @Override
    public Optional<LoanAccount> findAccount(UUID id) {
        return Optional.ofNullable(accounts.get(id));
    }

    @Override
    public Optional<LoanAccount> findByApplication(UUID applicationId) {
        UUID id = byApplication.get(applicationId);
        return id == null ? Optional.empty() : findAccount(id);
    }

    @Override
    public List<LoanAccount> listAccounts(UUID customerId, String status, int limit, int offset) {
        return accounts.values().stream()
                .filter(account -> customerId == null || customerId.equals(account.customerId()))
                .filter(account -> status == null || status.isBlank() || account.status().name().equals(status))
                .sorted(Comparator.comparing(LoanAccount::createdAt).reversed())
                .skip(offset)
                .limit(limit)
                .toList();
    }

    @Override
    public long countAccounts(UUID customerId, String status) {
        return listAccounts(customerId, status, Integer.MAX_VALUE, 0).size();
    }

    @Override
    public void updateAccount(LoanAccount account, int expectedVersion) {
        synchronized (lock(account.id())) {
            LoanAccount current = accounts.get(account.id());
            if (current == null) {
                throw new ResourceNotFoundException("Loan account");
            }
            if (current.version() != expectedVersion) {
                throw new ConflictException("The loan account was updated by another repayment");
            }
            accounts.put(account.id(), account);
        }
    }

    @Override
    public <T> T lockAccount(UUID id, Supplier<T> work) {
        synchronized (lock(id)) {
            if (!accounts.containsKey(id)) {
                throw new ResourceNotFoundException("Loan account");
            }
            return work.get();
        }
    }

    @Override
    public void insertSchedule(ScheduleVersion schedule) {
        schedules.put(schedule.id(), schedule);
    }

    @Override
    public void supersedeCurrent(UUID loanAccountId) {
        schedules.replaceAll((id, schedule) -> schedule.loanAccountId().equals(loanAccountId) && "CURRENT".equals(schedule.status())
                ? new ScheduleVersion(schedule.id(), schedule.loanAccountId(), schedule.versionNumber(), "SUPERSEDED", schedule.previousScheduleId(), schedule.engineVersion(), schedule.servicingVersion(), schedule.fingerprint(), schedule.parameters(), schedule.reason(), schedule.createdAt(), schedule.createdBy())
                : schedule);
    }

    @Override
    public Optional<ScheduleVersion> findSchedule(UUID loanAccountId, Integer versionNumber) {
        return schedules.values().stream()
                .filter(schedule -> schedule.loanAccountId().equals(loanAccountId))
                .filter(schedule -> versionNumber == null ? "CURRENT".equals(schedule.status()) : schedule.versionNumber() == versionNumber)
                .findFirst();
    }

    @Override
    public void insertLines(List<ScheduleLine> rows) {
        if (rows.isEmpty()) {
            return;
        }
        lines.put(rows.get(0).scheduleId(), new ArrayList<>(rows));
    }

    @Override
    public List<ScheduleLine> lines(UUID scheduleId) {
        return List.copyOf(lines.getOrDefault(scheduleId, List.of()));
    }

    @Override
    public void updateLine(ScheduleLine line) {
        List<ScheduleLine> rows = lines.get(line.scheduleId());
        if (rows == null) {
            throw new ResourceNotFoundException("Repayment schedule");
        }
        synchronized (rows) {
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).id().equals(line.id())) {
                    rows.set(index, line);
                    return;
                }
            }
        }
        throw new ResourceNotFoundException("Schedule item");
    }

    @Override
    public void insertSnapshot(BalanceSnapshot snapshot) {
        snapshots.computeIfAbsent(snapshot.loanAccountId(), ignored -> new ArrayList<>()).add(snapshot);
    }

    @Override
    public Optional<BalanceSnapshot> snapshotBefore(UUID loanAccountId, LocalDate date) {
        return snapshots.getOrDefault(loanAccountId, List.of()).stream()
                .filter(snapshot -> snapshot.valueDate().isBefore(date))
                .max(Comparator.comparing(BalanceSnapshot::valueDate).thenComparing(BalanceSnapshot::postedAt));
    }

    @Override
    public List<BalanceSnapshot> snapshots(UUID loanAccountId, LocalDate from, LocalDate to) {
        return snapshots.getOrDefault(loanAccountId, List.of()).stream()
                .filter(snapshot -> !snapshot.valueDate().isBefore(from) && !snapshot.valueDate().isAfter(to))
                .sorted(Comparator.comparing(BalanceSnapshot::postedAt))
                .toList();
    }

    @Override
    public void insertEvent(AccountEvent event) {
        events.computeIfAbsent(event.loanAccountId(), ignored -> new ArrayList<>()).add(event);
    }

    @Override
    public List<AccountEvent> events(UUID loanAccountId) {
        return List.copyOf(events.getOrDefault(loanAccountId, List.of()));
    }

    @Override
    public void insertAccrual(InterestAccrual accrual) {
        accruals.computeIfAbsent(accrual.loanAccountId(), ignored -> new ArrayList<>()).add(accrual);
    }

    @Override
    public List<InterestAccrual> accruals(UUID loanAccountId) {
        return List.copyOf(accruals.getOrDefault(loanAccountId, List.of()));
    }

    @Override
    public void insertCharge(LoanCharge charge) {
    }

    @Override
    public AllocationPolicy policyAt(LocalDate date) {
        synchronized (policies) {
            return policies.stream()
                    .filter(policy -> !policy.effectiveFrom().isAfter(date))
                    .max(Comparator.comparingInt(AllocationPolicy::versionNumber))
                    .orElseThrow(() -> new IllegalStateException("No repayment allocation policy is effective"));
        }
    }

    @Override
    public void savePolicy(AllocationPolicy policy) {
        synchronized (policies) {
            policies.add(policy);
        }
    }

    @Override
    public List<PostingRule> postingRules() {
        return StandardChart.rules();
    }

    @Override
    public void insertRepayment(LoanRepayment repayment) {
        synchronized (keys) {
            if (repaymentKeys.putIfAbsent(repayment.idempotencyKey(), repayment.id()) != null) {
                throw new ConflictException("A repayment with this idempotency key already exists");
            }
            repayments.put(repayment.id(), repayment);
        }
    }

    @Override
    public Optional<LoanRepayment> findRepayment(UUID id) {
        return Optional.ofNullable(repayments.get(id));
    }

    @Override
    public Optional<LoanRepayment> findRepaymentByKey(String idempotencyKey) {
        UUID id = repaymentKeys.get(idempotencyKey);
        return id == null ? Optional.empty() : findRepayment(id);
    }

    @Override
    public void updateRepayment(LoanRepayment repayment, int expectedVersion) {
        synchronized (keys) {
            LoanRepayment current = repayments.get(repayment.id());
            if (current == null || current.version() != expectedVersion) {
                throw new ConflictException("The repayment was updated by another request");
            }
            repayments.put(repayment.id(), repayment);
        }
    }

    @Override
    public List<LoanRepayment> repayments(UUID loanAccountId) {
        return repayments.values().stream().filter(repayment -> repayment.loanAccountId().equals(loanAccountId)).toList();
    }

    @Override
    public void insertAllocation(RepaymentAllocation allocation) {
        allocations.computeIfAbsent(allocation.repaymentId(), ignored -> new ArrayList<>()).add(allocation);
    }

    @Override
    public List<RepaymentAllocation> allocations(UUID repaymentId) {
        return List.copyOf(allocations.getOrDefault(repaymentId, List.of()));
    }

    @Override
    public void insertHistory(RepaymentHistory history) {
    }

    @Override
    public void insertJournal(JournalHeader header, List<JournalLine> rows) {
        journals.put(header.id(), header);
        journalLines.put(header.id(), new ArrayList<>(rows));
    }

    @Override
    public Optional<JournalHeader> findJournal(UUID id) {
        return Optional.ofNullable(journals.get(id));
    }

    @Override
    public List<JournalLine> journalLines(UUID journalId) {
        return List.copyOf(journalLines.getOrDefault(journalId, List.of()));
    }

    @Override
    public List<JournalHeader> listJournals(int limit, int offset) {
        return journals.values().stream().sorted(Comparator.comparing(JournalHeader::createdAt).reversed()).skip(offset).limit(limit).toList();
    }

    @Override
    public long countJournals() {
        return journals.size();
    }

    @Override
    public void postJournal(UUID id, UUID approvedBy, Instant postedAt) {
        JournalHeader current = journals.get(id);
        if (current == null || current.status() != JournalStatus.DRAFT) {
            throw new ConflictException("A posted journal cannot be edited");
        }
        journals.put(id, new JournalHeader(
                current.id(), current.reference(), current.loanAccountId(), current.eventCode(), current.currencyCode(),
                JournalStatus.POSTED, current.reversalOf(), current.requiresApproval(), current.requestedBy(), approvedBy, postedAt, current.createdAt()));
    }

    @Override
    public void insertAccountingEvent(AccountingEvent event) {
    }

    @Override
    public void insertReversal(TransactionReversal reversal) {
    }

    @Override
    public void insertAdjustment(InterestAdjustment adjustment) {
        adjustmentsByJournal.put(adjustment.journalId(), adjustment);
    }

    @Override
    public Optional<InterestAdjustment> findAdjustmentByJournal(UUID journalId) {
        return Optional.ofNullable(adjustmentsByJournal.get(journalId));
    }

    @Override
    public void updateAdjustment(InterestAdjustment adjustment) {
        adjustmentsByJournal.put(adjustment.journalId(), adjustment);
    }

    @Override
    public BigDecimal postedCash(UUID loanAccountId) {
        return repayments.values().stream()
                .filter(repayment -> repayment.status() == RepaymentStatus.POSTED)
                .filter(repayment -> loanAccountId == null || loanAccountId.equals(repayment.loanAccountId()))
                .map(LoanRepayment::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Object lock(UUID id) {
        return locks.computeIfAbsent(id, ignored -> new Object());
    }
}
