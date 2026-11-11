package com.mfb.lending.module.servicing.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.mfb.lending.module.servicing.calculation.AccountingEngine.PostingRule;
import com.mfb.lending.module.servicing.calculation.StandardChart.AllocationPolicy;
import com.mfb.lending.module.servicing.domain.AccountEvent;
import com.mfb.lending.module.servicing.domain.AccountingEvent;
import com.mfb.lending.module.servicing.domain.BalanceSnapshot;
import com.mfb.lending.module.servicing.domain.InterestAccrual;
import com.mfb.lending.module.servicing.domain.InterestAdjustment;
import com.mfb.lending.module.servicing.domain.JournalHeader;
import com.mfb.lending.module.servicing.domain.JournalLine;
import com.mfb.lending.module.servicing.domain.LoanAccount;
import com.mfb.lending.module.servicing.domain.LoanCharge;
import com.mfb.lending.module.servicing.domain.LoanRepayment;
import com.mfb.lending.module.servicing.domain.RepaymentAllocation;
import com.mfb.lending.module.servicing.domain.RepaymentHistory;
import com.mfb.lending.module.servicing.domain.ScheduleLine;
import com.mfb.lending.module.servicing.domain.ScheduleVersion;
import com.mfb.lending.module.servicing.domain.TransactionReversal;

public interface ServicingStore {

    String nextAccountReference();

    String nextRepaymentReference();

    String nextJournalReference();

    void insertAccount(LoanAccount account);

    Optional<LoanAccount> findAccount(UUID id);

    Optional<LoanAccount> findByApplication(UUID applicationId);

    List<LoanAccount> listAccounts(UUID customerId, String status, int limit, int offset);

    long countAccounts(UUID customerId, String status);

    void updateAccount(LoanAccount account, int expectedVersion);

    <T> T lockAccount(UUID id, Supplier<T> work);

    void insertSchedule(ScheduleVersion schedule);

    void supersedeCurrent(UUID loanAccountId);

    Optional<ScheduleVersion> findSchedule(UUID loanAccountId, Integer versionNumber);

    void insertLines(List<ScheduleLine> lines);

    List<ScheduleLine> lines(UUID scheduleId);

    void updateLine(ScheduleLine line);

    void insertSnapshot(BalanceSnapshot snapshot);

    Optional<BalanceSnapshot> snapshotBefore(UUID loanAccountId, LocalDate date);

    List<BalanceSnapshot> snapshots(UUID loanAccountId, LocalDate from, LocalDate to);

    void insertEvent(AccountEvent event);

    List<AccountEvent> events(UUID loanAccountId);

    void insertAccrual(InterestAccrual accrual);

    List<InterestAccrual> accruals(UUID loanAccountId);

    void insertCharge(LoanCharge charge);

    AllocationPolicy policyAt(LocalDate date);

    void savePolicy(AllocationPolicy policy);

    List<PostingRule> postingRules();

    void insertRepayment(LoanRepayment repayment);

    Optional<LoanRepayment> findRepayment(UUID id);

    Optional<LoanRepayment> findRepaymentByKey(String idempotencyKey);

    void updateRepayment(LoanRepayment repayment, int expectedVersion);

    List<LoanRepayment> repayments(UUID loanAccountId);

    void insertAllocation(RepaymentAllocation allocation);

    List<RepaymentAllocation> allocations(UUID repaymentId);

    void insertHistory(RepaymentHistory history);

    void insertJournal(JournalHeader header, List<JournalLine> lines);

    Optional<JournalHeader> findJournal(UUID id);

    List<JournalLine> journalLines(UUID journalId);

    List<JournalHeader> listJournals(int limit, int offset);

    long countJournals();

    void postJournal(UUID id, UUID approvedBy, Instant postedAt);

    void insertAccountingEvent(AccountingEvent event);

    void insertReversal(TransactionReversal reversal);

    void insertAdjustment(InterestAdjustment adjustment);

    Optional<InterestAdjustment> findAdjustmentByJournal(UUID journalId);

    void updateAdjustment(InterestAdjustment adjustment);

    BigDecimal postedCash(UUID loanAccountId);
}
