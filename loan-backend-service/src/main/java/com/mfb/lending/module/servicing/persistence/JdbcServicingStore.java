package com.mfb.lending.module.servicing.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.servicing.calculation.AccountingEngine.PostingRule;
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
import com.mfb.lending.module.servicing.domain.LoanAccountStatus;
import com.mfb.lending.module.servicing.domain.LoanCharge;
import com.mfb.lending.module.servicing.domain.LoanRepayment;
import com.mfb.lending.module.servicing.domain.RepaymentAllocation;
import com.mfb.lending.module.servicing.domain.RepaymentChannel;
import com.mfb.lending.module.servicing.domain.RepaymentHistory;
import com.mfb.lending.module.servicing.domain.RepaymentStatus;
import com.mfb.lending.module.servicing.domain.ScheduleLine;
import com.mfb.lending.module.servicing.domain.ScheduleVersion;
import com.mfb.lending.module.servicing.domain.TransactionReversal;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcServicingStore implements ServicingStore {

    private static final String ACCOUNT_COLUMNS = """
            id, reference, application_id, customer_id, branch_id, product_id, product_version_id, offer_id,
            currency_code, status, original_principal, outstanding_principal, accrued_interest, outstanding_interest,
            outstanding_fees, outstanding_penalties, overpayment, disbursement_date, maturity_date, accrual_start,
            last_accrued_through, grace_period_days, penalty_grace_days, penalty_basis, penalty_rate, penalty_amount,
            early_repayment_rule, early_repayment_fee_rate, interest_method, interest_period, interest_rate,
            repayment_frequency, day_count_convention, rounding_scale, rounding_method, accrual_frequency,
            repayment_day_of_month, holiday_rule, core_disbursement_reference, current_schedule_id, schedule_version,
            version, activated_at, closed_at, created_at, updated_at, created_by
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcServicingStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String nextAccountReference() {
        return next("loan_account_reference_seq", "LA");
    }

    @Override
    public String nextRepaymentReference() {
        return next("loan_repayment_reference_seq", "RP");
    }

    @Override
    public String nextJournalReference() {
        return next("journal_reference_seq", "JE");
    }

    @Override
    public void insertAccount(LoanAccount account) {
        try {
            jdbc.update(
                    """
                    INSERT INTO loan_accounts (
                        id, reference, application_id, customer_id, branch_id, product_id, product_version_id, offer_id,
                        currency_code, status, original_principal, outstanding_principal, accrued_interest, outstanding_interest,
                        outstanding_fees, outstanding_penalties, overpayment, disbursement_date, maturity_date, accrual_start,
                        last_accrued_through, grace_period_days, penalty_grace_days, penalty_basis, penalty_rate, penalty_amount,
                        early_repayment_rule, early_repayment_fee_rate, interest_method, interest_period, interest_rate,
                        repayment_frequency, day_count_convention, rounding_scale, rounding_method, accrual_frequency,
                        repayment_day_of_month, holiday_rule, core_disbursement_reference, current_schedule_id, schedule_version,
                        version, activated_at, closed_at, created_by)
                    VALUES (
                        :id, :reference, :applicationId, :customerId, :branchId, :productId, :productVersionId, :offerId,
                        :currencyCode, :status, :originalPrincipal, :outstandingPrincipal, :accruedInterest, :outstandingInterest,
                        :outstandingFees, :outstandingPenalties, :overpayment, :disbursementDate, :maturityDate, :accrualStart,
                        :lastAccruedThrough, :gracePeriodDays, :penaltyGraceDays, :penaltyBasis, :penaltyRate, :penaltyAmount,
                        :earlyRepaymentRule, :earlyRepaymentFeeRate, :interestMethod, :interestPeriod, :interestRate,
                        :repaymentFrequency, :dayCountConvention, :scale, :roundingMethod, :accrualFrequency,
                        :repaymentDayOfMonth, :holidayRule, :coreDisbursementReference, :currentScheduleId, :scheduleVersion,
                        :version, :activatedAt, :closedAt, :createdBy)
                    """,
                    accountParams(account));
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("A loan account already exists for this application");
        }
    }

    @Override
    public Optional<LoanAccount> findAccount(UUID id) {
        return one("SELECT " + ACCOUNT_COLUMNS + " FROM loan_accounts WHERE id = :id", new MapSqlParameterSource("id", id), this::account);
    }

    @Override
    public Optional<LoanAccount> findByApplication(UUID applicationId) {
        return one("SELECT " + ACCOUNT_COLUMNS + " FROM loan_accounts WHERE application_id = :id", new MapSqlParameterSource("id", applicationId), this::account);
    }

    @Override
    public List<LoanAccount> listAccounts(UUID customerId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + ACCOUNT_COLUMNS + " FROM loan_accounts WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset);
        String selected = blank(status);
        if (customerId != null) {
            sql.append(" AND customer_id = :customerId");
            params.addValue("customerId", customerId);
        }
        if (selected != null) {
            sql.append(" AND status = :status");
            params.addValue("status", selected);
        }
        sql.append(" ORDER BY created_at DESC LIMIT :limit OFFSET :offset");
        return jdbc.query(sql.toString(), params, (rs, row) -> account(rs));
    }

    @Override
    public long countAccounts(UUID customerId, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM loan_accounts WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        String selected = blank(status);
        if (customerId != null) {
            sql.append(" AND customer_id = :customerId");
            params.addValue("customerId", customerId);
        }
        if (selected != null) {
            sql.append(" AND status = :status");
            params.addValue("status", selected);
        }
        Long total = jdbc.queryForObject(sql.toString(), params, Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public void updateAccount(LoanAccount account, int expectedVersion) {
        int updated = jdbc.update(
                """
                UPDATE loan_accounts SET
                    status = :status, outstanding_principal = :outstandingPrincipal, accrued_interest = :accruedInterest,
                    outstanding_interest = :outstandingInterest, outstanding_fees = :outstandingFees,
                    outstanding_penalties = :outstandingPenalties, overpayment = :overpayment, maturity_date = :maturityDate,
                    accrual_start = :accrualStart, last_accrued_through = :lastAccruedThrough, interest_rate = :interestRate,
                    current_schedule_id = :currentScheduleId, schedule_version = :scheduleVersion, version = :version,
                    activated_at = :activatedAt, closed_at = :closedAt
                WHERE id = :id AND version = :expectedVersion
                """,
                accountParams(account).addValue("expectedVersion", expectedVersion));
        if (updated != 1) {
            throw new ConflictException("The loan account was updated by another repayment");
        }
    }

    @Override
    public <T> T lockAccount(UUID id, Supplier<T> work) {
        List<UUID> rows = jdbc.query(
                "SELECT id FROM loan_accounts WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id),
                (rs, row) -> rs.getObject("id", UUID.class));
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Loan account");
        }
        return work.get();
    }

    @Override
    public void insertSchedule(ScheduleVersion schedule) {
        jdbc.update(
                """
                INSERT INTO loan_repayment_schedules (
                    id, loan_account_id, version_number, status, previous_schedule_id, engine_version, servicing_version,
                    fingerprint, parameters, reason, created_by)
                VALUES (
                    :id, :loanAccountId, :versionNumber, :status, :previousScheduleId, :engineVersion, :servicingVersion,
                    :fingerprint, CAST(:parameters AS jsonb), :reason, :createdBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", schedule.id())
                        .addValue("loanAccountId", schedule.loanAccountId())
                        .addValue("versionNumber", schedule.versionNumber())
                        .addValue("status", schedule.status())
                        .addValue("previousScheduleId", schedule.previousScheduleId())
                        .addValue("engineVersion", schedule.engineVersion())
                        .addValue("servicingVersion", schedule.servicingVersion())
                        .addValue("fingerprint", schedule.fingerprint())
                        .addValue("parameters", schedule.parameters())
                        .addValue("reason", schedule.reason())
                        .addValue("createdBy", schedule.createdBy()));
    }

    @Override
    public void supersedeCurrent(UUID loanAccountId) {
        jdbc.update(
                "UPDATE loan_repayment_schedules SET status = 'SUPERSEDED' WHERE loan_account_id = :id AND status = 'CURRENT'",
                new MapSqlParameterSource("id", loanAccountId));
    }

    @Override
    public Optional<ScheduleVersion> findSchedule(UUID loanAccountId, Integer versionNumber) {
        String sql = versionNumber == null
                ? "SELECT * FROM loan_repayment_schedules WHERE loan_account_id = :id AND status = 'CURRENT'"
                : "SELECT * FROM loan_repayment_schedules WHERE loan_account_id = :id AND version_number = :version";
        return one(sql, new MapSqlParameterSource().addValue("id", loanAccountId).addValue("version", versionNumber), this::schedule);
    }

    @Override
    public void insertLines(List<ScheduleLine> lines) {
        for (ScheduleLine line : lines) {
            jdbc.update(
                    """
                    INSERT INTO loan_schedule_items (
                        id, schedule_id, instalment_number, contractual_due_date, due_date, opening_balance, principal_due,
                        interest_due, instalment_amount, closing_balance, principal_paid, interest_paid, penalty_accrued, status)
                    VALUES (
                        :id, :scheduleId, :number, :contractualDueDate, :dueDate, :openingBalance, :principalDue,
                        :interestDue, :instalmentAmount, :closingBalance, :principalPaid, :interestPaid, :penaltyAccrued, :status)
                    """,
                    lineParams(line));
        }
    }

    @Override
    public List<ScheduleLine> lines(UUID scheduleId) {
        return jdbc.query(
                "SELECT * FROM loan_schedule_items WHERE schedule_id = :id ORDER BY instalment_number",
                new MapSqlParameterSource("id", scheduleId),
                (rs, row) -> line(rs));
    }

    @Override
    public void updateLine(ScheduleLine line) {
        jdbc.update(
                """
                UPDATE loan_schedule_items
                SET principal_paid = :principalPaid, interest_paid = :interestPaid, penalty_accrued = :penaltyAccrued, status = :status
                WHERE id = :id
                """,
                lineParams(line));
    }

    @Override
    public void insertSnapshot(BalanceSnapshot snapshot) {
        jdbc.update(
                """
                INSERT INTO loan_account_balances (
                    id, loan_account_id, value_date, outstanding_principal, accrued_interest, outstanding_interest,
                    outstanding_fees, outstanding_penalties, overpayment, reason, posted_at)
                VALUES (
                    :id, :loanAccountId, :valueDate, :outstandingPrincipal, :accruedInterest, :outstandingInterest,
                    :outstandingFees, :outstandingPenalties, :overpayment, :reason, :postedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", snapshot.id())
                        .addValue("loanAccountId", snapshot.loanAccountId())
                        .addValue("valueDate", snapshot.valueDate())
                        .addValue("outstandingPrincipal", snapshot.outstandingPrincipal())
                        .addValue("accruedInterest", snapshot.accruedInterest())
                        .addValue("outstandingInterest", snapshot.outstandingInterest())
                        .addValue("outstandingFees", snapshot.outstandingFees())
                        .addValue("outstandingPenalties", snapshot.outstandingPenalties())
                        .addValue("overpayment", snapshot.overpayment())
                        .addValue("reason", snapshot.reason())
                        .addValue("postedAt", snapshot.postedAt()));
    }

    @Override
    public Optional<BalanceSnapshot> snapshotBefore(UUID loanAccountId, LocalDate date) {
        return one(
                """
                SELECT * FROM loan_account_balances
                WHERE loan_account_id = :id AND value_date < :date
                ORDER BY value_date DESC, posted_at DESC LIMIT 1
                """,
                new MapSqlParameterSource().addValue("id", loanAccountId).addValue("date", date),
                this::snapshot);
    }

    @Override
    public List<BalanceSnapshot> snapshots(UUID loanAccountId, LocalDate from, LocalDate to) {
        return jdbc.query(
                """
                SELECT * FROM loan_account_balances
                WHERE loan_account_id = :id AND value_date >= :from AND value_date <= :to
                ORDER BY posted_at
                """,
                new MapSqlParameterSource().addValue("id", loanAccountId).addValue("from", from).addValue("to", to),
                (rs, row) -> snapshot(rs));
    }

    @Override
    public void insertEvent(AccountEvent event) {
        jdbc.update(
                """
                INSERT INTO loan_account_events (id, loan_account_id, event_type, payload, actor_id, occurred_at)
                VALUES (:id, :loanAccountId, :eventType, CAST(:payload AS jsonb), :actorId, :occurredAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", event.id())
                        .addValue("loanAccountId", event.loanAccountId())
                        .addValue("eventType", event.eventType())
                        .addValue("payload", event.payload())
                        .addValue("actorId", event.actorId())
                        .addValue("occurredAt", event.occurredAt()));
    }

    @Override
    public List<AccountEvent> events(UUID loanAccountId) {
        return jdbc.query(
                "SELECT * FROM loan_account_events WHERE loan_account_id = :id ORDER BY occurred_at",
                new MapSqlParameterSource("id", loanAccountId),
                (rs, row) -> new AccountEvent(rs.getObject("id", UUID.class), rs.getObject("loan_account_id", UUID.class), rs.getString("event_type"), rs.getString("payload"), rs.getObject("actor_id", UUID.class), instant(rs, "occurred_at")));
    }

    @Override
    public void insertAccrual(InterestAccrual accrual) {
        jdbc.update(
                """
                INSERT INTO interest_accruals (
                    id, loan_account_id, accrual_date, frequency, component, amount, day_count_convention, journal_id, created_at)
                VALUES (
                    :id, :loanAccountId, :accrualDate, :frequency, :component, :amount, :dayCountConvention, :journalId, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", accrual.id())
                        .addValue("loanAccountId", accrual.loanAccountId())
                        .addValue("accrualDate", accrual.accrualDate())
                        .addValue("frequency", accrual.frequency())
                        .addValue("component", accrual.component())
                        .addValue("amount", accrual.amount())
                        .addValue("dayCountConvention", accrual.dayCountConvention())
                        .addValue("journalId", accrual.journalId())
                        .addValue("createdAt", accrual.createdAt()));
    }

    @Override
    public List<InterestAccrual> accruals(UUID loanAccountId) {
        return jdbc.query(
                "SELECT * FROM interest_accruals WHERE loan_account_id = :id ORDER BY accrual_date",
                new MapSqlParameterSource("id", loanAccountId),
                (rs, row) -> new InterestAccrual(
                        rs.getObject("id", UUID.class), rs.getObject("loan_account_id", UUID.class), rs.getObject("accrual_date", LocalDate.class),
                        rs.getString("frequency"), rs.getString("component"), rs.getBigDecimal("amount"), rs.getString("day_count_convention"),
                        rs.getObject("journal_id", UUID.class), instant(rs, "created_at")));
    }

    @Override
    public void insertCharge(LoanCharge charge) {
        jdbc.update(
                """
                INSERT INTO loan_charges (id, loan_account_id, charge_type, amount, journal_id, reason, created_at)
                VALUES (:id, :loanAccountId, :chargeType, :amount, :journalId, :reason, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", charge.id())
                        .addValue("loanAccountId", charge.loanAccountId())
                        .addValue("chargeType", charge.chargeType())
                        .addValue("amount", charge.amount())
                        .addValue("journalId", charge.journalId())
                        .addValue("reason", charge.reason())
                        .addValue("createdAt", charge.createdAt()));
    }

    @Override
    public AllocationPolicy policyAt(LocalDate date) {
        List<AllocationPolicy> rows = jdbc.query(
                """
                SELECT version_number, effective_from, priority
                FROM repayment_allocation_policies
                WHERE effective_from <= :date
                ORDER BY version_number DESC
                LIMIT 1
                """,
                new MapSqlParameterSource("date", date),
                (rs, row) -> AllocationPolicy.parse(rs.getInt("version_number"), rs.getObject("effective_from", LocalDate.class), rs.getString("priority")));
        if (rows.isEmpty()) {
            throw new IllegalStateException("No repayment allocation policy is effective");
        }
        return rows.get(0);
    }

    @Override
    public void savePolicy(AllocationPolicy policy) {
        jdbc.update(
                """
                INSERT INTO repayment_allocation_policies (id, version_number, priority, effective_from, approved_by)
                VALUES (:id, :versionNumber, :priority, :effectiveFrom, :approvedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("versionNumber", policy.versionNumber())
                        .addValue("priority", policy.encoded())
                        .addValue("effectiveFrom", policy.effectiveFrom())
                        .addValue("approvedBy", "credit-policy"));
    }

    @Override
    public List<PostingRule> postingRules() {
        return jdbc.query(
                """
                SELECT event_code, component, direction, account_code, requires_approval, version_number
                FROM accounting_posting_rules
                WHERE version_number = (SELECT MAX(version_number) FROM accounting_posting_rules)
                ORDER BY event_code, line_number
                """,
                new MapSqlParameterSource(),
                (rs, row) -> new PostingRule(rs.getString("event_code"), rs.getString("component"), rs.getString("direction"), rs.getString("account_code"), rs.getBoolean("requires_approval"), rs.getInt("version_number")));
    }

    @Override
    public void insertRepayment(LoanRepayment repayment) {
        try {
            jdbc.update(
                    """
                    INSERT INTO loan_repayments (
                        id, reference, loan_account_id, idempotency_key, channel, status, amount, value_date, settlement,
                        fee_charged, core_transaction_id, core_reference, external_reference, cash_journal_id, fee_journal_id,
                        allocation_policy_version, version, failure_reason, actor_id, actor_name)
                    VALUES (
                        :id, :reference, :loanAccountId, :idempotencyKey, :channel, :status, :amount, :valueDate, :settlement,
                        :feeCharged, :coreTransactionId, :coreReference, :externalReference, :cashJournalId, :feeJournalId,
                        :allocationPolicyVersion, :version, :failureReason, :actorId, :actorName)
                    """,
                    repaymentParams(repayment));
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("A repayment with this idempotency key already exists");
        }
    }

    @Override
    public Optional<LoanRepayment> findRepayment(UUID id) {
        return one("SELECT * FROM loan_repayments WHERE id = :id", new MapSqlParameterSource("id", id), this::repayment);
    }

    @Override
    public Optional<LoanRepayment> findRepaymentByKey(String idempotencyKey) {
        return one("SELECT * FROM loan_repayments WHERE idempotency_key = :key", new MapSqlParameterSource("key", idempotencyKey), this::repayment);
    }

    @Override
    public void updateRepayment(LoanRepayment repayment, int expectedVersion) {
        int updated = jdbc.update(
                """
                UPDATE loan_repayments SET
                    status = :status, fee_charged = :feeCharged, core_transaction_id = :coreTransactionId,
                    core_reference = :coreReference, cash_journal_id = :cashJournalId, fee_journal_id = :feeJournalId,
                    version = :version, failure_reason = :failureReason
                WHERE id = :id AND version = :expectedVersion
                """,
                repaymentParams(repayment).addValue("expectedVersion", expectedVersion));
        if (updated != 1) {
            throw new ConflictException("The repayment was updated by another request");
        }
    }

    @Override
    public List<LoanRepayment> repayments(UUID loanAccountId) {
        return jdbc.query(
                "SELECT * FROM loan_repayments WHERE loan_account_id = :id ORDER BY created_at",
                new MapSqlParameterSource("id", loanAccountId),
                (rs, row) -> repayment(rs));
    }

    @Override
    public void insertAllocation(RepaymentAllocation allocation) {
        jdbc.update(
                """
                INSERT INTO loan_repayment_allocations (id, repayment_id, component, amount, schedule_item_id, currency_code)
                VALUES (:id, :repaymentId, :component, :amount, :scheduleLineId, :currencyCode)
                """,
                new MapSqlParameterSource()
                        .addValue("id", allocation.id())
                        .addValue("repaymentId", allocation.repaymentId())
                        .addValue("component", allocation.component())
                        .addValue("amount", allocation.amount())
                        .addValue("scheduleLineId", allocation.scheduleLineId())
                        .addValue("currencyCode", allocation.currencyCode()));
    }

    @Override
    public List<RepaymentAllocation> allocations(UUID repaymentId) {
        return jdbc.query(
                "SELECT * FROM loan_repayment_allocations WHERE repayment_id = :id ORDER BY component",
                new MapSqlParameterSource("id", repaymentId),
                (rs, row) -> new RepaymentAllocation(rs.getObject("id", UUID.class), rs.getObject("repayment_id", UUID.class), rs.getString("component"), rs.getBigDecimal("amount"), rs.getObject("schedule_item_id", UUID.class), rs.getString("currency_code")));
    }

    @Override
    public void insertHistory(RepaymentHistory history) {
        jdbc.update(
                """
                INSERT INTO loan_repayment_history (id, repayment_id, from_status, to_status, reason, occurred_at)
                VALUES (:id, :repaymentId, :fromStatus, :toStatus, :reason, :occurredAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", history.id())
                        .addValue("repaymentId", history.repaymentId())
                        .addValue("fromStatus", history.fromStatus())
                        .addValue("toStatus", history.toStatus())
                        .addValue("reason", history.reason())
                        .addValue("occurredAt", history.occurredAt()));
    }

    @Override
    public void insertJournal(JournalHeader header, List<JournalLine> lines) {
        jdbc.update(
                """
                INSERT INTO journal_entries (
                    id, reference, loan_account_id, event_code, currency_code, status, reversal_of, requires_approval,
                    requested_by, approved_by, posted_at)
                VALUES (
                    :id, :reference, :loanAccountId, :eventCode, :currencyCode, :status, :reversalOf, :requiresApproval,
                    :requestedBy, :approvedBy, :postedAt)
                """,
                journalParams(header));
        for (JournalLine line : lines) {
            jdbc.update(
                    """
                    INSERT INTO journal_entry_lines (id, journal_entry_id, line_number, account_code, direction, amount, currency_code, narration)
                    VALUES (:id, :journalId, :lineNumber, :accountCode, :direction, :amount, :currencyCode, :narration)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", line.id())
                            .addValue("journalId", line.journalId())
                            .addValue("lineNumber", line.lineNumber())
                            .addValue("accountCode", line.accountCode())
                            .addValue("direction", line.direction())
                            .addValue("amount", line.amount())
                            .addValue("currencyCode", line.currencyCode())
                            .addValue("narration", line.narration()));
        }
    }

    @Override
    public Optional<JournalHeader> findJournal(UUID id) {
        return one("SELECT * FROM journal_entries WHERE id = :id", new MapSqlParameterSource("id", id), this::journal);
    }

    @Override
    public List<JournalLine> journalLines(UUID journalId) {
        return jdbc.query(
                "SELECT * FROM journal_entry_lines WHERE journal_entry_id = :id ORDER BY line_number",
                new MapSqlParameterSource("id", journalId),
                (rs, row) -> new JournalLine(rs.getObject("id", UUID.class), rs.getObject("journal_entry_id", UUID.class), rs.getInt("line_number"), rs.getString("account_code"), rs.getString("direction"), rs.getBigDecimal("amount"), rs.getString("currency_code"), rs.getString("narration")));
    }

    @Override
    public List<JournalHeader> listJournals(int limit, int offset) {
        return jdbc.query(
                "SELECT * FROM journal_entries ORDER BY created_at DESC LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset),
                (rs, row) -> journal(rs));
    }

    @Override
    public long countJournals() {
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM journal_entries", new MapSqlParameterSource(), Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public void postJournal(UUID id, UUID approvedBy, Instant postedAt) {
        int updated = jdbc.update(
                """
                UPDATE journal_entries
                SET status = 'POSTED', approved_by = :approvedBy, posted_at = :postedAt
                WHERE id = :id AND status = 'DRAFT'
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("approvedBy", approvedBy).addValue("postedAt", postedAt));
        if (updated != 1) {
            throw new ConflictException("A posted journal cannot be edited");
        }
    }

    @Override
    public void insertAccountingEvent(AccountingEvent event) {
        jdbc.update(
                """
                INSERT INTO accounting_events (id, loan_account_id, event_code, source_id, journal_id, payload, created_at)
                VALUES (:id, :loanAccountId, :eventCode, :sourceId, :journalId, :payload, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", event.id())
                        .addValue("loanAccountId", event.loanAccountId())
                        .addValue("eventCode", event.eventCode())
                        .addValue("sourceId", event.sourceId())
                        .addValue("journalId", event.journalId())
                        .addValue("payload", event.payload())
                        .addValue("createdAt", event.createdAt()));
    }

    @Override
    public void insertReversal(TransactionReversal reversal) {
        jdbc.update(
                """
                INSERT INTO transaction_reversals (id, repayment_id, original_journal_id, reversal_journal_id, reason, created_at)
                VALUES (:id, :repaymentId, :originalJournalId, :reversalJournalId, :reason, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", reversal.id())
                        .addValue("repaymentId", reversal.repaymentId())
                        .addValue("originalJournalId", reversal.originalJournalId())
                        .addValue("reversalJournalId", reversal.reversalJournalId())
                        .addValue("reason", reversal.reason())
                        .addValue("createdAt", reversal.createdAt()));
    }

    @Override
    public void insertAdjustment(InterestAdjustment adjustment) {
        jdbc.update(
                """
                INSERT INTO interest_adjustments (id, loan_account_id, amount, reason, journal_id, status, requested_by, approved_by, created_at)
                VALUES (:id, :loanAccountId, :amount, :reason, :journalId, :status, :requestedBy, :approvedBy, :createdAt)
                """,
                adjustmentParams(adjustment));
    }

    @Override
    public Optional<InterestAdjustment> findAdjustmentByJournal(UUID journalId) {
        return one("SELECT * FROM interest_adjustments WHERE journal_id = :id", new MapSqlParameterSource("id", journalId), this::adjustment);
    }

    @Override
    public void updateAdjustment(InterestAdjustment adjustment) {
        jdbc.update(
                "UPDATE interest_adjustments SET status = :status, approved_by = :approvedBy WHERE id = :id AND status = 'PENDING'",
                adjustmentParams(adjustment));
    }

    @Override
    public BigDecimal postedCash(UUID loanAccountId) {
        String sql = loanAccountId == null
                ? "SELECT COALESCE(SUM(amount), 0) FROM loan_repayments WHERE status = 'POSTED'"
                : "SELECT COALESCE(SUM(amount), 0) FROM loan_repayments WHERE status = 'POSTED' AND loan_account_id = :loanAccountId";
        BigDecimal total = jdbc.queryForObject(sql, new MapSqlParameterSource("loanAccountId", loanAccountId), BigDecimal.class);
        return total == null ? BigDecimal.ZERO : total;
    }

    private String next(String sequence, String prefix) {
        Long value = jdbc.queryForObject("SELECT nextval('" + sequence + "')", new MapSqlParameterSource(), Long.class);
        return prefix + String.format("%012d", value == null ? 1 : value);
    }

    private MapSqlParameterSource accountParams(LoanAccount account) {
        return new MapSqlParameterSource()
                .addValue("id", account.id())
                .addValue("reference", account.reference())
                .addValue("applicationId", account.applicationId())
                .addValue("customerId", account.customerId())
                .addValue("branchId", account.branchId())
                .addValue("productId", account.productId())
                .addValue("productVersionId", account.productVersionId())
                .addValue("offerId", account.offerId())
                .addValue("currencyCode", account.currencyCode())
                .addValue("status", account.status().name())
                .addValue("originalPrincipal", account.originalPrincipal())
                .addValue("outstandingPrincipal", account.outstandingPrincipal())
                .addValue("accruedInterest", account.accruedInterest())
                .addValue("outstandingInterest", account.outstandingInterest())
                .addValue("outstandingFees", account.outstandingFees())
                .addValue("outstandingPenalties", account.outstandingPenalties())
                .addValue("overpayment", account.overpayment())
                .addValue("disbursementDate", account.disbursementDate())
                .addValue("maturityDate", account.maturityDate())
                .addValue("accrualStart", account.accrualStart())
                .addValue("lastAccruedThrough", account.lastAccruedThrough())
                .addValue("gracePeriodDays", account.gracePeriodDays())
                .addValue("penaltyGraceDays", account.penaltyGraceDays())
                .addValue("penaltyBasis", account.penaltyBasis())
                .addValue("penaltyRate", account.penaltyRate())
                .addValue("penaltyAmount", account.penaltyAmount())
                .addValue("earlyRepaymentRule", account.earlyRepaymentRule())
                .addValue("earlyRepaymentFeeRate", account.earlyRepaymentFeeRate())
                .addValue("interestMethod", account.interestMethod())
                .addValue("interestPeriod", account.interestPeriod())
                .addValue("interestRate", account.interestRate())
                .addValue("repaymentFrequency", account.repaymentFrequency())
                .addValue("dayCountConvention", account.dayCountConvention())
                .addValue("scale", account.scale())
                .addValue("roundingMethod", account.roundingMethod())
                .addValue("accrualFrequency", account.accrualFrequency())
                .addValue("repaymentDayOfMonth", account.repaymentDayOfMonth())
                .addValue("holidayRule", account.holidayRule())
                .addValue("coreDisbursementReference", account.coreDisbursementReference())
                .addValue("currentScheduleId", account.currentScheduleId())
                .addValue("scheduleVersion", account.scheduleVersion())
                .addValue("version", account.version())
                .addValue("activatedAt", account.activatedAt())
                .addValue("closedAt", account.closedAt())
                .addValue("createdBy", account.createdBy());
    }

    private MapSqlParameterSource lineParams(ScheduleLine line) {
        return new MapSqlParameterSource()
                .addValue("id", line.id())
                .addValue("scheduleId", line.scheduleId())
                .addValue("number", line.number())
                .addValue("contractualDueDate", line.contractualDueDate())
                .addValue("dueDate", line.dueDate())
                .addValue("openingBalance", line.openingBalance())
                .addValue("principalDue", line.principalDue())
                .addValue("interestDue", line.interestDue())
                .addValue("instalmentAmount", line.instalmentAmount())
                .addValue("closingBalance", line.closingBalance())
                .addValue("principalPaid", line.principalPaid())
                .addValue("interestPaid", line.interestPaid())
                .addValue("penaltyAccrued", line.penaltyAccrued())
                .addValue("status", line.status());
    }

    private MapSqlParameterSource repaymentParams(LoanRepayment repayment) {
        return new MapSqlParameterSource()
                .addValue("id", repayment.id())
                .addValue("reference", repayment.reference())
                .addValue("loanAccountId", repayment.loanAccountId())
                .addValue("idempotencyKey", repayment.idempotencyKey())
                .addValue("channel", repayment.channel().name())
                .addValue("status", repayment.status().name())
                .addValue("amount", repayment.amount())
                .addValue("valueDate", repayment.valueDate())
                .addValue("settlement", repayment.settlement())
                .addValue("feeCharged", repayment.feeCharged())
                .addValue("coreTransactionId", repayment.coreTransactionId())
                .addValue("coreReference", repayment.coreReference())
                .addValue("externalReference", repayment.externalReference())
                .addValue("cashJournalId", repayment.cashJournalId())
                .addValue("feeJournalId", repayment.feeJournalId())
                .addValue("allocationPolicyVersion", repayment.allocationPolicyVersion())
                .addValue("version", repayment.version())
                .addValue("failureReason", repayment.failureReason())
                .addValue("actorId", repayment.actorId())
                .addValue("actorName", repayment.actorName());
    }

    private MapSqlParameterSource journalParams(JournalHeader header) {
        return new MapSqlParameterSource()
                .addValue("id", header.id())
                .addValue("reference", header.reference())
                .addValue("loanAccountId", header.loanAccountId())
                .addValue("eventCode", header.eventCode())
                .addValue("currencyCode", header.currencyCode())
                .addValue("status", header.status().name())
                .addValue("reversalOf", header.reversalOf())
                .addValue("requiresApproval", header.requiresApproval())
                .addValue("requestedBy", header.requestedBy())
                .addValue("approvedBy", header.approvedBy())
                .addValue("postedAt", header.postedAt());
    }

    private MapSqlParameterSource adjustmentParams(InterestAdjustment adjustment) {
        return new MapSqlParameterSource()
                .addValue("id", adjustment.id())
                .addValue("loanAccountId", adjustment.loanAccountId())
                .addValue("amount", adjustment.amount())
                .addValue("reason", adjustment.reason())
                .addValue("journalId", adjustment.journalId())
                .addValue("status", adjustment.status())
                .addValue("requestedBy", adjustment.requestedBy())
                .addValue("approvedBy", adjustment.approvedBy())
                .addValue("createdAt", adjustment.createdAt());
    }

    private LoanAccount account(ResultSet rs) throws SQLException {
        Integer repaymentDay = rs.getObject("repayment_day_of_month", Integer.class);
        return new LoanAccount(
                rs.getObject("id", UUID.class), rs.getString("reference"), rs.getObject("application_id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getObject("branch_id", UUID.class), rs.getObject("product_id", UUID.class),
                rs.getObject("product_version_id", UUID.class), rs.getObject("offer_id", UUID.class), rs.getString("currency_code"),
                LoanAccountStatus.valueOf(rs.getString("status")), rs.getBigDecimal("original_principal"), rs.getBigDecimal("outstanding_principal"),
                rs.getBigDecimal("accrued_interest"), rs.getBigDecimal("outstanding_interest"), rs.getBigDecimal("outstanding_fees"),
                rs.getBigDecimal("outstanding_penalties"), rs.getBigDecimal("overpayment"), rs.getObject("disbursement_date", LocalDate.class),
                rs.getObject("maturity_date", LocalDate.class), rs.getObject("accrual_start", LocalDate.class), rs.getObject("last_accrued_through", LocalDate.class),
                rs.getInt("grace_period_days"), rs.getInt("penalty_grace_days"), rs.getString("penalty_basis"), rs.getBigDecimal("penalty_rate"),
                rs.getBigDecimal("penalty_amount"), rs.getString("early_repayment_rule"), rs.getBigDecimal("early_repayment_fee_rate"),
                rs.getString("interest_method"), rs.getString("interest_period"), rs.getBigDecimal("interest_rate"), rs.getString("repayment_frequency"),
                rs.getString("day_count_convention"), rs.getInt("rounding_scale"), rs.getString("rounding_method"), rs.getString("accrual_frequency"),
                repaymentDay, rs.getString("holiday_rule"), rs.getString("core_disbursement_reference"), rs.getObject("current_schedule_id", UUID.class),
                rs.getInt("schedule_version"), rs.getInt("version"), instant(rs, "activated_at"), instant(rs, "closed_at"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getString("created_by"));
    }

    private ScheduleVersion schedule(ResultSet rs) throws SQLException {
        return new ScheduleVersion(
                rs.getObject("id", UUID.class), rs.getObject("loan_account_id", UUID.class), rs.getInt("version_number"), rs.getString("status"),
                rs.getObject("previous_schedule_id", UUID.class), rs.getString("engine_version"), rs.getString("servicing_version"),
                rs.getString("fingerprint"), rs.getString("parameters"), rs.getString("reason"), instant(rs, "created_at"), rs.getString("created_by"));
    }

    private ScheduleLine line(ResultSet rs) throws SQLException {
        return new ScheduleLine(
                rs.getObject("id", UUID.class), rs.getObject("schedule_id", UUID.class), rs.getInt("instalment_number"),
                rs.getObject("contractual_due_date", LocalDate.class), rs.getObject("due_date", LocalDate.class), rs.getBigDecimal("opening_balance"),
                rs.getBigDecimal("principal_due"), rs.getBigDecimal("interest_due"), rs.getBigDecimal("instalment_amount"), rs.getBigDecimal("closing_balance"),
                rs.getBigDecimal("principal_paid"), rs.getBigDecimal("interest_paid"), rs.getBigDecimal("penalty_accrued"), rs.getString("status"));
    }

    private BalanceSnapshot snapshot(ResultSet rs) throws SQLException {
        return new BalanceSnapshot(
                rs.getObject("id", UUID.class), rs.getObject("loan_account_id", UUID.class), rs.getObject("value_date", LocalDate.class),
                rs.getBigDecimal("outstanding_principal"), rs.getBigDecimal("accrued_interest"), rs.getBigDecimal("outstanding_interest"),
                rs.getBigDecimal("outstanding_fees"), rs.getBigDecimal("outstanding_penalties"), rs.getBigDecimal("overpayment"),
                rs.getString("reason"), instant(rs, "posted_at"));
    }

    private LoanRepayment repayment(ResultSet rs) throws SQLException {
        return new LoanRepayment(
                rs.getObject("id", UUID.class), rs.getString("reference"), rs.getObject("loan_account_id", UUID.class), rs.getString("idempotency_key"),
                RepaymentChannel.valueOf(rs.getString("channel")), RepaymentStatus.valueOf(rs.getString("status")), rs.getBigDecimal("amount"),
                rs.getObject("value_date", LocalDate.class), rs.getBoolean("settlement"), rs.getBigDecimal("fee_charged"),
                rs.getObject("core_transaction_id", UUID.class), rs.getString("core_reference"), rs.getString("external_reference"),
                rs.getObject("cash_journal_id", UUID.class), rs.getObject("fee_journal_id", UUID.class), rs.getInt("allocation_policy_version"),
                rs.getInt("version"), rs.getString("failure_reason"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getObject("actor_id", UUID.class), rs.getString("actor_name"));
    }

    private JournalHeader journal(ResultSet rs) throws SQLException {
        return new JournalHeader(
                rs.getObject("id", UUID.class), rs.getString("reference"), rs.getObject("loan_account_id", UUID.class), rs.getString("event_code"),
                rs.getString("currency_code"), JournalStatus.valueOf(rs.getString("status")), rs.getObject("reversal_of", UUID.class),
                rs.getBoolean("requires_approval"), rs.getObject("requested_by", UUID.class), rs.getObject("approved_by", UUID.class),
                instant(rs, "posted_at"), instant(rs, "created_at"));
    }

    private InterestAdjustment adjustment(ResultSet rs) throws SQLException {
        return new InterestAdjustment(
                rs.getObject("id", UUID.class), rs.getObject("loan_account_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("reason"),
                rs.getObject("journal_id", UUID.class), rs.getString("status"), rs.getObject("requested_by", UUID.class),
                rs.getObject("approved_by", UUID.class), instant(rs, "created_at"));
    }

    private <T> Optional<T> one(String sql, MapSqlParameterSource params, RowReader<T> reader) {
        List<T> rows = jdbc.query(sql, params, (rs, row) -> reader.read(rs));
        return rows.stream().findFirst();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet resultSet) throws SQLException;
    }
}
